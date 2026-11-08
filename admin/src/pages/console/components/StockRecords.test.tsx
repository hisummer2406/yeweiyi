import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
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
const addManualStockEvent = vi.fn();
const deleteManualStockEvent = vi.fn();
vi.mock('@/services/ant-design-pro/stock', () => ({
  listStockEvents,
  addManualStockEvent,
  deleteManualStockEvent,
}));
const getDeviceTanks = vi.fn();
vi.mock('@/services/ant-design-pro/tankGrants', () => ({ getDeviceTanks }));

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

beforeEach(() => {
  vi.clearAllMocks();
  getDeviceTanks.mockResolvedValue({
    deviceName: 'D1',
    reported: true,
    tanks: [{ index: 1 }, { index: 2 }],
  });
});
afterEach(cleanup);

describe('StockRecords', () => {
  it('kg column is the device delivered mass; missing is a dash, never the weight difference (cloud#85)', async () => {
    listStockEvents.mockResolvedValue(
      result({
        rows: [
          ev('a', { oilType: 'A', deliveredWeightKg: 5600.123 }),
          ev('b', { oilType: 'B', deliveredWeightKg: null }),
          ev('c', { oilType: 'Kerosene', oilTypeIndex: 10 }),
        ],
      }),
    );
    render(<StockRecords device="D1" />);
    const cellsOf = async (text: string) => {
      const cells = within(
        (await screen.findByText(text)).closest('tr') as HTMLElement,
      ).getAllByRole('cell');
      return cells.map((c) => c.textContent);
    };
    const a = await cellsOf('A');
    const kg = screen
      .getAllByRole('columnheader')
      .findIndex((h) => h.textContent === '实际进出量(kg)');
    expect(kg).toBeGreaterThan(0);
    expect(a[kg]).toBe('5600.12');
    expect((await cellsOf('B'))[kg]).toBe('—');
    expect(await screen.findByText('米糠油')).toBeTruthy();
  });

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

  it('the next offset is page × 20, not the rows shown (the cloud skips unreadable rows after paging)', async () => {
    listStockEvents
      .mockResolvedValueOnce(
        result({
          rows: Array.from({ length: 15 }, (_, i) =>
            ev(`e${i}`, { oilType: `P1-${i}` }),
          ),
          truncated: true,
        }),
      )
      .mockResolvedValueOnce(
        result({ rows: [ev('n1', { oilType: 'P2' })], truncated: true }),
      )
      .mockResolvedValue(result({ rows: [ev('n2', { oilType: 'P3' })] }));
    render(<StockRecords device="D1" />);
    await screen.findByText('P1-0');
    expect(listStockEvents.mock.calls[0][0]).toMatchObject({
      limit: 20,
      offset: 0,
    });
    fireEvent.click(screen.getByText('下一页'));
    await screen.findByText('P2');
    expect(listStockEvents.mock.calls[1][0]).toMatchObject({ offset: 20 });
    fireEvent.click(screen.getByText('下一页'));
    await screen.findByText('P3');
    expect(listStockEvents.mock.calls[2][0]).toMatchObject({ offset: 40 });
  });

  it('truncated means more follow: a next page, not a warning', async () => {
    listStockEvents.mockResolvedValue(
      result({ rows: [ev('e1')], truncated: true }),
    );
    render(<StockRecords device="D1" />);
    expect(await screen.findByText('下一页')).toBeTruthy();
    expect(screen.queryByText(/请缩小时间范围/)).toBeNull();
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

  it('asks the cloud for the direction of the page (#84)', async () => {
    listStockEvents.mockResolvedValue(result({ rows: [ev('e1')] }));
    render(<StockRecords device="D1" direction="OUT" />);
    await waitFor(() => expect(listStockEvents).toHaveBeenCalled());
    expect(listStockEvents.mock.calls[0][0].direction).toBe('OUT');
    expect(listStockEvents.mock.calls[0][0].tank).toBeUndefined();
  });

  it('a refused tank (403 tank_not_authorized) goes back to all tanks and says so', async () => {
    listStockEvents.mockResolvedValue(result({ rows: [ev('e1')] }));
    render(<StockRecords device="D1" />);
    await screen.findByText('92#');
    listStockEvents.mockRejectedValueOnce(
      new ApiError(
        403,
        'not authorized for this tank',
        false,
        'tank_not_authorized',
      ),
    );
    // pick tank 2 in the select
    fireEvent.mouseDown(await screen.findByLabelText('油罐'));
    fireEvent.click(await screen.findByText('2号罐'));
    await waitFor(() =>
      expect(listStockEvents.mock.calls.some((c) => c[0].tank === 2)).toBe(
        true,
      ),
    );
    expect(
      await screen.findByText('2号罐已无权查看，已切回全部油罐'),
    ).toBeTruthy();
    await waitFor(() =>
      expect(listStockEvents.mock.calls.at(-1)?.[0].tank).toBeUndefined(),
    );
  });
});

describe('StockRecords manual entry (cloud#69)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getDeviceTanks.mockResolvedValue({
      deviceName: 'D1',
      reported: true,
      tanks: [{ index: 1 }, { index: 2 }],
    });
  });

  it('offers revoke only on cloud entries and shows null amounts as dashes', async () => {
    listStockEvents.mockResolvedValue(
      result({
        rows: [
          ev('dev-1'),
          ev('manual-1', {
            origin: 'cloud',
            source: 'MANUAL',
            endLevelMm: null,
            beginVolumeL: null,
            endVolumeL: null,
          }),
        ],
      }),
    );
    deleteManualStockEvent.mockResolvedValue(undefined);
    render(<StockRecords device="D1" />);
    const revoke = await screen.findAllByRole('button', { name: /撤\s*销/ });
    expect(revoke).toHaveLength(1);
    expect(screen.getByText('100.0 → —')).toBeTruthy();
    fireEvent.click(revoke[0]);
    fireEvent.click(await screen.findByRole('button', { name: /确\s*定|OK/ }));
    await waitFor(() =>
      expect(deleteManualStockEvent).toHaveBeenCalledWith('D1', 'manual-1'),
    );
  });

  it('submits a fixed-direction entry with a requestId and retries a timeout with the same one', async () => {
    listStockEvents.mockResolvedValue(result());
    addManualStockEvent
      .mockRejectedValueOnce(new ApiError(0))
      .mockResolvedValue({ row: ev('manual-2'), replayed: true });
    render(<StockRecords device="D1" direction="OUT" />);
    fireEvent.click(await screen.findByRole('button', { name: /补\s*录/ }));
    const dialog = await screen.findByRole('dialog');
    // Tank
    fireEvent.mouseDown(within(dialog).getByLabelText('罐号'));
    fireEvent.click(await screen.findByTitle('2 号罐'));
    // Time: type into the picker input
    const timeInput = within(dialog).getByPlaceholderText(/请选择|Select/);
    fireEvent.mouseDown(timeInput);
    fireEvent.change(timeInput, { target: { value: '2026-11-01 08:30' } });
    fireEvent.keyDown(timeInput, { key: 'Enter', code: 'Enter' });
    fireEvent.change(within(dialog).getByLabelText('前高度'), {
      target: { value: '1333' },
    });
    fireEvent.change(within(dialog).getByLabelText('升数（正数）'), {
      target: { value: '540' },
    });
    fireEvent.change(within(dialog).getByLabelText('补录原因'), {
      target: { value: '断电漏记' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: '保存补录' }));
    await waitFor(() => expect(addManualStockEvent).toHaveBeenCalledTimes(1));
    const [device, body] = addManualStockEvent.mock.calls[0];
    expect(device).toBe('D1');
    expect(body).toMatchObject({
      direction: 'OUT',
      tank: 2,
      beforeLevelMm: 1333,
      volumeL: 540,
      reason: '断电漏记',
    });
    expect(body.time).toMatch(/^2026-11-01T08:30:00[+-]\d{2}:\d{2}$/);
    expect(body.requestId).toMatch(/^[A-Za-z0-9_-]{1,64}$/);
    // Codex review PR #16: closing and reopening keeps the unresolved submission (same id, same body),
    // and the retry sends that snapshot even if a field was edited meanwhile.
    await within(dialog).findByRole('button', { name: '用同一提交编号重试' });
    fireEvent.click(within(dialog).getByRole('button', { name: /取\s*消|Cancel/ }));
    fireEvent.click(await screen.findByRole('button', { name: /补\s*录/ }));
    const reopened = await screen.findByRole('dialog');
    fireEvent.click(
      await within(reopened).findByRole('button', { name: '用同一提交编号重试' }),
    );
    await waitFor(() => expect(addManualStockEvent).toHaveBeenCalledTimes(2));
    expect(addManualStockEvent.mock.calls[1][1]).toEqual(body);
  });
});
