import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import zhPages from '@/locales/zh-CN/pages';

const listShiftRecords = vi.fn();
const listInventoryRecords = vi.fn();

vi.mock('@umijs/max', () => ({
  useIntl: () => ({
    formatMessage: ({ id }: { id: string }, values?: Record<string, string>) =>
      ((zhPages as Record<string, string>)[id] ?? id).replace(
        /\{(\w+)\}/g,
        (_, k) => values?.[k] ?? '',
      ),
  }),
}));
vi.mock('@/services/ant-design-pro/syncedRecords', () => ({
  listShiftRecords,
  listInventoryRecords,
}));
const getDeviceTanks = vi.fn();
vi.mock('@/services/ant-design-pro/tankGrants', () => ({ getDeviceTanks }));

const { default: ShiftRecords } = await import('./ShiftRecords');
const { default: InventoryRecords } = await import('./InventoryRecords');

const list = <R,>(
  rows: R[],
  p: Partial<API.SyncedRecordList<R>> = {},
): API.SyncedRecordList<R> => ({
  rows,
  truncated: false,
  partial: false,
  unparsable: 0,
  syncStatus: 'SYNCED',
  ...p,
});

const shift = (p: Partial<API.ShiftRecord> = {}): API.ShiftRecord => ({
  shiftId: 's1',
  status: 'CLOSED',
  beginTime: '2026-11-03T00:00:00Z',
  endTime: '2026-11-03T08:00:00Z',
  tank: 1,
  oilType: '92#',
  oilTypeIndex: 5,
  beginLevelMm: 1200,
  beginVolumeL: 15000.5,
  endLevelMm: 1100,
  endVolumeL: 13800,
  outVolumeL: 1200.5,
  outWeightKg: 890.4,
  ...p,
});

const snap = (p: Partial<API.InventoryRecord> = {}): API.InventoryRecord => ({
  recordId: 'r1',
  time: '2026-11-03T08:00:00Z',
  trigger: 'SCHEDULED',
  tank: 3,
  oilType: 'Diesel',
  oilTypeIndex: null,
  levelMm: 1500.555,
  volumeL: 20000,
  waterLevelMm: 0,
  tempC: -3.2,
  weightKg: null,
  ...p,
});

const headers = () =>
  screen.getAllByRole('columnheader').map((h) => h.textContent);
const cells = (row: HTMLElement) =>
  within(row)
    .getAllByRole('cell')
    .map((c) => c.textContent);

beforeEach(() => {
  vi.clearAllMocks();
  getDeviceTanks.mockResolvedValue({
    deviceName: 'D1',
    reported: true,
    tanks: [],
  });
});
afterEach(cleanup);

describe('ShiftRecords', () => {
  it('columns in display order; open shift shows dashes, never 0', async () => {
    listShiftRecords.mockResolvedValue(
      list([
        shift({
          shiftId: 's2',
          status: 'OPEN',
          endTime: null,
          endLevelMm: null,
          endVolumeL: null,
          outVolumeL: null,
          outWeightKg: null,
        }),
        shift({ outWeightKg: 0 }),
      ]),
    );
    render(<ShiftRecords device="D1" />);
    await screen.findByText('进行中');
    expect(headers()).toEqual([
      '油罐',
      '介质',
      '交班时间',
      '接班时间',
      '前高(mm)',
      '前量(L)',
      '后高(mm)',
      '后量(L)',
      '出库量(L)',
      '出库量(kg)',
    ]);
    const rows = screen.getAllByRole('row').slice(1);
    const open = cells(rows[0]);
    expect(open[1]).toBe('92#');
    expect(open[2]).toBe('—进行中');
    expect(open.slice(4)).toEqual(['1200', '15000.5', '—', '—', '—', '—']);
    expect(cells(rows[1]).slice(4)).toEqual([
      '1200',
      '15000.5',
      '1100',
      '13800',
      '1200.5',
      '0',
    ]);
    expect(listShiftRecords.mock.calls[0][0].device).toBe('D1');
  });

  it('rows of one shift share a stripe, the next shift alternates', async () => {
    listShiftRecords.mockResolvedValue(
      list([
        shift({ shiftId: 'a', tank: 1 }),
        shift({ shiftId: 'a', tank: 2 }),
        shift({ shiftId: 'b', tank: 1 }),
      ]),
    );
    render(<ShiftRecords device="D1" />);
    await screen.findAllByText('92#');
    const stripes = screen
      .getAllByRole('row')
      .slice(1)
      .map((r) => r.getAttribute('data-stripe'));
    expect(stripes).toEqual(['0', '0', '1']);
  });

  it('UNSUPPORTED says so and does not claim there are no records', async () => {
    listShiftRecords.mockResolvedValue(list([], { syncStatus: 'UNSUPPORTED' }));
    render(<ShiftRecords device="D1" />);
    await screen.findByText('设备暂不支持该类记录');
    expect(screen.queryByText('该时间范围内没有交班记录')).toBeNull();
  });

  it('SYNCING with no rows warns instead of "no records"', async () => {
    listShiftRecords.mockResolvedValue(list([], { syncStatus: 'SYNCING' }));
    render(<ShiftRecords device="D1" />);
    await screen.findByText('记录同步中，列表可能不完整');
    expect(screen.queryByText('该时间范围内没有交班记录')).toBeNull();
  });

  it('an unknown status is "cannot tell"', async () => {
    listShiftRecords.mockResolvedValue(list([], { syncStatus: 'BACKFILLING' }));
    render(<ShiftRecords device="D1" />);
    await screen.findByText('无法确认记录是否已同步完整，列表可能不完整');
    expect(screen.queryByText('该时间范围内没有交班记录')).toBeNull();
  });

  it('only SYNCED and empty says there are no records', async () => {
    listShiftRecords.mockResolvedValue(list([]));
    render(<ShiftRecords device="D1" />);
    await screen.findByText('该时间范围内没有交班记录');
  });

  it('unparsable is reported with its count', async () => {
    listShiftRecords.mockResolvedValue(list([shift()], { unparsable: 4 }));
    render(<ShiftRecords device="D1" />);
    await screen.findByText('本设备有 4 条记录无法解析，列表可能不完整');
  });
});

describe('InventoryRecords', () => {
  it('columns in display order, mm levels, 0 water stays 0, missing mass is a dash', async () => {
    listInventoryRecords.mockResolvedValue(list([snap()]));
    render(<InventoryRecords device="D1" />);
    await screen.findByText('Diesel');
    expect(headers()).toEqual([
      '油罐',
      '介质',
      '库存生成时间',
      '液位(mm)',
      '容积(L)',
      '水高(mm)',
      '温度(℃)',
      '质量(kg)',
    ]);
    const row = cells(screen.getAllByRole('row')[1]);
    expect(row[0]).toBe('3');
    expect(row.slice(3)).toEqual(['1500.56', '20000', '0', '-3.2', '—']);
  });

  it('medium: builtin index in the UI language, else raw text, else a dash', async () => {
    listInventoryRecords.mockResolvedValue(
      list([
        snap({ recordId: 'a', oilTypeIndex: 10, oilType: 'Kerosene' }),
        snap({ recordId: 'b', oilTypeIndex: 0, oilType: null }),
      ]),
    );
    render(<InventoryRecords device="D1" />);
    await screen.findByText('米糠油');
    expect(cells(screen.getAllByRole('row')[2])[1]).toBe('—');
  });
});
