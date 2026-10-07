import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import zhPages from '@/locales/zh-CN/pages';

const listStockEvents = vi.fn();

vi.mock('@umijs/max', () => ({
  useIntl: () => ({
    formatMessage: ({ id }: { id: string }, values?: Record<string, string>) =>
      ((zhPages as Record<string, string>)[id] ?? id).replace(
        /\{(\w+)\}/g,
        (_, k) => values?.[k] ?? '',
      ),
  }),
}));
vi.mock('@/services/ant-design-pro/stock', () => ({ listStockEvents }));

const { default: StockRecords } = await import('./StockRecords');
const { ApiError } = await import('@/services/ant-design-pro/apiError');

const ev = (id: string, p: Partial<API.StockEvent> = {}): API.StockEvent => ({
  eventId: id,
  tank: 1,
  direction: 'IN',
  oilType: '92#',
  source: 'AUTO',
  beginTime: '2026-10-06T00:00:00Z',
  endTime: '2026-10-06T01:00:00Z',
  beginLevelMm: 100,
  endLevelMm: 200,
  beginVolumeL: 1000,
  endVolumeL: 2000,
  beginTempC: 20,
  endTempC: 20,
  beginWeightKg: 800,
  endWeightKg: 1600,
  netVolumeL: 1000,
  deliveredVolumeL: 1000,
  time: '2026-10-06T01:00:00Z',
  ...p,
});
const result = (p: Partial<API.StockList> = {}): API.StockList => ({
  rows: [],
  parsed: 0,
  unparsable: 0,
  legacySkipped: 0,
  invalidSource: 0,
  folded: 0,
  truncated: false,
  partial: false,
  ...p,
});

beforeEach(() => vi.clearAllMocks());
afterEach(cleanup);

describe('StockRecords', () => {
  it('requests the given device for the last 24 hours', async () => {
    listStockEvents.mockResolvedValue(result({ rows: [ev('e1')] }));
    render(<StockRecords device="D1" />);
    await screen.findByText('92#');
    const q: API.RecordsQuery = listStockEvents.mock.calls[0][0];
    expect(q.device).toBe('D1');
    expect(Date.parse(q.endTime) - Date.parse(q.beginTime)).toBe(86_400_000);
  });

  it('on the stock-in page shows only IN rows', async () => {
    listStockEvents.mockResolvedValue(
      result({
        rows: [
          ev('i1', { oilType: 'IN-OIL' }),
          ev('o1', { direction: 'OUT', oilType: 'OUT-OIL' }),
          ev('i2', { oilType: 'IN-OIL-2' }),
        ],
      }),
    );
    render(<StockRecords device="D1" direction="IN" />);
    await screen.findByText('IN-OIL');
    expect(screen.getByText('IN-OIL-2')).toBeTruthy();
    expect(screen.queryByText('OUT-OIL')).toBeNull();
  });

  it('on the stock-out page shows only OUT rows', async () => {
    listStockEvents.mockResolvedValue(
      result({
        rows: [
          ev('i1', { oilType: 'IN-OIL' }),
          ev('o1', { direction: 'OUT', oilType: 'OUT-OIL' }),
        ],
      }),
    );
    render(<StockRecords device="D1" direction="OUT" />);
    await screen.findByText('OUT-OIL');
    expect(screen.queryByText('IN-OIL')).toBeNull();
  });

  it('without a direction (device detail tab) shows both', async () => {
    listStockEvents.mockResolvedValue(
      result({
        rows: [
          ev('i1', { oilType: 'IN-OIL' }),
          ev('o1', { direction: 'OUT', oilType: 'OUT-OIL' }),
        ],
      }),
    );
    render(<StockRecords device="D1" />);
    await screen.findByText('IN-OIL');
    expect(screen.getByText('OUT-OIL')).toBeTruthy();
  });

  it('shows the source labels', async () => {
    listStockEvents.mockResolvedValue(
      result({
        rows: [ev('a', { source: 'AUTO' }), ev('m', { source: 'MANUAL' })],
      }),
    );
    render(<StockRecords device="D1" />);
    await screen.findAllByText('自动');
    expect(screen.getByText('手动')).toBeTruthy();
  });

  it('a clean empty window shows the empty state', async () => {
    listStockEvents.mockResolvedValue(result());
    render(<StockRecords device="D1" />);
    expect(await screen.findByText('该时间范围内没有出入库记录')).toBeTruthy();
  });

  it('IN page with only OUT rows in a clean window shows the empty state', async () => {
    listStockEvents.mockResolvedValue(
      result({ rows: [ev('o1', { direction: 'OUT' })] }),
    );
    render(<StockRecords device="D1" direction="IN" />);
    expect(await screen.findByText('该时间范围内没有出入库记录')).toBeTruthy();
  });

  it('legacySkipped is reported and the parsed rows still show', async () => {
    listStockEvents.mockResolvedValue(
      result({ rows: [ev('e1')], legacySkipped: 4 }),
    );
    render(<StockRecords device="D1" />);
    expect(
      await screen.findByText(/有 4 条升级前的旧记录无法显示/),
    ).toBeTruthy();
    expect(screen.getByText('92#')).toBeTruthy();
  });

  it('a 502 with no rows says the firmware is not upgraded and is NOT shown as an empty table', async () => {
    listStockEvents.mockResolvedValue(
      result({
        unparsable: 3,
        degraded: {
          code: 'STOCK_EVENT_FORMAT_UNSUPPORTED',
          device: 'D1',
          sample: 'DetailInoutRecord{x}',
        },
      }),
    );
    render(<StockRecords device="D1" />);
    expect(
      await screen.findByText(/设备固件尚未升级，部分记录无法读取/),
    ).toBeTruthy();
    expect(
      screen.getByText(/设备 D1，样例：DetailInoutRecord\{x\}/),
    ).toBeTruthy();
    expect(screen.queryByText('该时间范围内没有出入库记录')).toBeNull();
  });

  it('a 502 that still carries rows shows the error and those rows', async () => {
    listStockEvents.mockResolvedValue(
      result({
        rows: [ev('e1'), ev('e2', { oilType: '95#' })],
        unparsable: 1,
        degraded: { code: 'STOCK_EVENT_FORMAT_UNSUPPORTED' },
      }),
    );
    render(<StockRecords device="D1" />);
    expect(await screen.findByText(/设备固件尚未升级/)).toBeTruthy();
    expect(screen.getByText('92#')).toBeTruthy();
    expect(screen.getByText('95#')).toBeTruthy();
  });

  it('partial says the records may be incomplete, and an empty partial list is not "no records"', async () => {
    listStockEvents.mockResolvedValue(result({ partial: true }));
    render(<StockRecords device="D1" />);
    expect(await screen.findByText(/首次同步尚未完成/)).toBeTruthy();
    expect(screen.queryByText('该时间范围内没有出入库记录')).toBeNull();
  });

  it('truncated asks to narrow the range', async () => {
    listStockEvents.mockResolvedValue(
      result({ rows: [ev('e1')], truncated: true }),
    );
    render(<StockRecords device="D1" />);
    expect(
      await screen.findByText(/只显示最新 500 条，请缩小时间范围/),
    ).toBeTruthy();
  });

  it('reports invalidSource and folded counts', async () => {
    listStockEvents.mockResolvedValue(
      result({ rows: [ev('e1')], invalidSource: 2, folded: 5 }),
    );
    render(<StockRecords device="D1" />);
    expect(
      await screen.findByText(/有 2 条记录的来源异常，未显示/),
    ).toBeTruthy();
    expect(screen.getByText(/有 5 条更正记录已合并/)).toBeTruthy();
  });

  it('a failure shows an error with retry, not the empty state', async () => {
    listStockEvents.mockRejectedValueOnce(new ApiError(500, 'boom'));
    render(<StockRecords device="D1" />);
    expect(await screen.findByText('记录加载失败')).toBeTruthy();
    expect(screen.queryByText('该时间范围内没有出入库记录')).toBeNull();
    listStockEvents.mockResolvedValue(result({ rows: [ev('e1')] }));
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(await screen.findByText('92#')).toBeTruthy();
  });

  it('stays silent when the auth failure was already handled', async () => {
    listStockEvents.mockRejectedValue(new ApiError(401, undefined, true));
    render(<StockRecords device="D1" />);
    await waitFor(() => expect(listStockEvents).toHaveBeenCalled());
    await new Promise((r) => setTimeout(r, 30));
    expect(screen.queryByText('记录加载失败')).toBeNull();
    expect(screen.queryByText('该时间范围内没有出入库记录')).toBeNull();
  });
});
