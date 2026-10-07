import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import zhPages from '@/locales/zh-CN/pages';

const getLatestTelemetry = vi.fn();

vi.mock('@umijs/max', () => ({
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

beforeEach(() => vi.clearAllMocks());
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
