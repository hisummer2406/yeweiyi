import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
} from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import zhPages from '@/locales/zh-CN/pages';

const getLatestTelemetry = vi.fn();

let query = '';
const setParams = vi.fn();
vi.mock('@umijs/max', () => ({
  useSearchParams: () => [new URLSearchParams(query), setParams],
  useIntl: () => ({
    formatMessage: ({ id }: { id: string }, values?: Record<string, string>) =>
      ((zhPages as Record<string, string>)[id] ?? id).replace(
        /\{(\w+)\}/g,
        (_, k) => values?.[k] ?? '',
      ),
  }),
}));
vi.mock('@/services/ant-design-pro/telemetry', () => ({ getLatestTelemetry }));

const { default: SnapshotTab } = await import('./SnapshotTab');
const { REFRESH_MS } = await import('../components/useRecordsQuery');

const telemetryOf = (oilType: string): API.LatestTelemetry => ({
  deviceName: 'x',
  ts: '2026-10-07T01:02:03Z',
  snapshotSeq: 1,
  tanks: [
    {
      index: 1,
      oilType,
      oilLevelMm: 100,
      waterLevelMm: 0,
      tempC: 20,
      oilVolumeL: 500,
      waterVolumeL: 0,
      oilWeightKg: 400,
      nullVolumeL: 0,
      oilRatioPct: 10,
      waterRatioPct: 0,
      alarmState: 'NORMAL',
      probeConnected: true,
    },
  ],
});

beforeEach(() => {
  vi.clearAllMocks();
  query = '';
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('SnapshotTab', () => {
  it('drops a late response of the previous device after switching', async () => {
    let resolveD1!: (v: API.LatestTelemetry) => void;
    getLatestTelemetry.mockImplementation((d: string) =>
      d === 'D1'
        ? new Promise((r) => {
            resolveD1 = r;
          })
        : Promise.resolve(telemetryOf('OIL-OF-D2')),
    );
    const { rerender } = render(<SnapshotTab device="D1" />);
    rerender(<SnapshotTab device="D2" />);
    expect(await screen.findByText(/OIL-OF-D2/)).toBeTruthy();
    await act(async () => resolveD1(telemetryOf('LATE-OIL-OF-D1')));
    expect(screen.queryByText(/LATE-OIL-OF-D1/)).toBeNull();
    expect(screen.getByText(/OIL-OF-D2/)).toBeTruthy();
  });

  it('refreshes every 30 seconds without falling back to the skeleton', async () => {
    vi.useFakeTimers();
    getLatestTelemetry.mockResolvedValue(telemetryOf('OIL'));
    render(<SnapshotTab device="D1" />);
    await act(async () => {});
    expect(getLatestTelemetry).toHaveBeenCalledTimes(1);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(REFRESH_MS);
    });
    expect(getLatestTelemetry).toHaveBeenCalledTimes(2);
    expect(screen.getByText(/OIL/)).toBeTruthy();
  });

  it('keeps the last good data when a background refresh fails', async () => {
    vi.useFakeTimers();
    getLatestTelemetry
      .mockResolvedValueOnce(telemetryOf('GOOD-OIL'))
      .mockRejectedValueOnce({ response: { status: 500, data: {} } });
    render(<SnapshotTab device="D1" />);
    await act(async () => {});
    await act(async () => {
      await vi.advanceTimersByTimeAsync(REFRESH_MS);
    });
    expect(screen.getByText(/GOOD-OIL/)).toBeTruthy();
    expect(screen.queryByText('实时数据加载失败')).toBeNull();
  });

  it('stops polling after unmount', async () => {
    vi.useFakeTimers();
    getLatestTelemetry.mockResolvedValue(telemetryOf('OIL'));
    const { unmount } = render(<SnapshotTab device="D1" />);
    await act(async () => {});
    unmount();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(REFRESH_MS * 3);
    });
    expect(getLatestTelemetry).toHaveBeenCalledTimes(1);
  });
});

describe('SnapshotTab one tank at a time', () => {
  const tank = (
    index: number,
    oilType: string,
    over: Partial<API.Tank> = {},
  ): API.Tank => ({
    index,
    oilType,
    oilLevelMm: 100 * index,
    waterLevelMm: 3,
    tempC: 20,
    oilVolumeL: 500,
    waterVolumeL: 7,
    oilWeightKg: 400,
    nullVolumeL: 900,
    oilRatioPct: 40,
    waterRatioPct: 2,
    alarmState: 'NORMAL',
    probeConnected: true,
    ...over,
  });
  const twoTanks = (): API.LatestTelemetry => ({
    deviceName: 'D',
    ts: '2026-10-07T01:02:03Z',
    snapshotSeq: 1,
    tanks: [tank(1, '92#'), tank(3, '0#柴油')],
  });

  it('shows only the tank that was clicked, with all of its readings', async () => {
    query = 'tank=3';
    getLatestTelemetry.mockResolvedValue(twoTanks());
    render(<SnapshotTab device="D" />);
    expect(await screen.findByText(/3号罐 0#柴油/)).toBeTruthy();
    expect(screen.queryByText(/1号罐 92#/)).toBeNull();
    // 全部读数：液位、容积、质量、温度、水高、水体积、空容、油品占比、水占比
    for (const label of [
      '液位',
      '容积',
      '质量',
      '温度',
      '水高',
      '水体积',
      '空容',
      '油品占比',
      '水占比',
    ]) {
      expect(screen.getAllByText(label).length).toBeGreaterThan(0);
    }
    expect(screen.getByText('300.0 mm')).toBeTruthy(); // 3 号罐的液位，不是 1 号罐的 100
    expect(screen.getByText('900.0 L')).toBeTruthy();
  });

  it('without ?tank= shows the lowest tank', async () => {
    getLatestTelemetry.mockResolvedValue(twoTanks());
    render(<SnapshotTab device="D" />);
    expect(await screen.findByText(/1号罐 92#/)).toBeTruthy();
    expect(screen.queryByText(/3号罐 0#柴油/)).toBeNull();
  });

  it('says so and shows the lowest tank when the requested one is not visible', async () => {
    query = 'tank=9';
    getLatestTelemetry.mockResolvedValue(twoTanks());
    render(<SnapshotTab device="D" />);
    expect(
      await screen.findByText(/9号罐当前不可查看.*改为显示 1号罐/),
    ).toBeTruthy();
    expect(screen.getByText(/1号罐 92#/)).toBeTruthy();
  });

  it('switching tank only changes ?tank= (replace)', async () => {
    query = 'tank=1&station=7';
    getLatestTelemetry.mockResolvedValue(twoTanks());
    render(<SnapshotTab device="D" />);
    await screen.findByText(/1号罐 92#/);
    fireEvent.click(screen.getByText('3号 0#柴油'));
    expect(setParams).toHaveBeenCalledTimes(1);
    const [next, opts] = setParams.mock.calls[0];
    expect(next.get('tank')).toBe('3');
    expect(next.get('station')).toBe('7');
    expect(opts).toEqual({ replace: true });
  });

  it('a single tank has no switcher', async () => {
    getLatestTelemetry.mockResolvedValue({
      ...twoTanks(),
      tanks: [tank(2, '95#')],
    });
    render(<SnapshotTab device="D" />);
    await screen.findByText(/2号罐 95#/);
    expect(screen.queryByText('2号 95#')).toBeNull();
  });

  it('keeps alarm and probe state as text, and a missing value as --', async () => {
    getLatestTelemetry.mockResolvedValue({
      ...twoTanks(),
      tanks: [
        tank(1, '92#', {
          alarmState: 'ALARM',
          probeConnected: false,
          tempC: Number.NaN,
        }),
      ],
    });
    render(<SnapshotTab device="D" />);
    await screen.findByText(/1号罐 92#/);
    expect(screen.getByText(/报警状态：/)).toBeTruthy();
    expect(screen.getByText(/探针：未连接/)).toBeTruthy();
    expect(screen.getAllByText('--').length).toBeGreaterThan(0);
  });

  it('no tank at all says so', async () => {
    getLatestTelemetry.mockResolvedValue({ ...twoTanks(), tanks: [] });
    render(<SnapshotTab device="D" />);
    expect(await screen.findByText('该设备当前没有可查看的罐。')).toBeTruthy();
  });
});
