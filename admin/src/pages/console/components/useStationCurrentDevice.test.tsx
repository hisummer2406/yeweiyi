import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const listDevices = vi.fn();
vi.mock('@/services/ant-design-pro/devices', () => ({ listDevices }));

const { useStationCurrentDevice } = await import('./useStationCurrentDevice');

const row = (name: string, station_id: number | null): API.DeviceRow => ({
  device_name: name,
  station_id,
  is_online: true,
  last_online_at: null,
  last_offline_at: null,
  updated_at: null,
});

beforeEach(() => {
  vi.useFakeTimers();
  vi.clearAllMocks();
});
afterEach(() => vi.useRealTimers());

describe('useStationCurrentDevice', () => {
  it('discards a slow poll that resolves after a newer one (A must not overwrite B)', async () => {
    listDevices.mockResolvedValueOnce([row('A', 5), row('B', null)]);
    const { result } = renderHook(() => useStationCurrentDevice(5));
    await act(async () => {});
    expect(result.current).toBe('A');

    let releaseSlow: (rows: API.DeviceRow[]) => void = () => {};
    listDevices.mockImplementationOnce(
      () => new Promise<API.DeviceRow[]>((r) => (releaseSlow = r)),
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000); // 慢请求，仍是 A
    });
    listDevices.mockResolvedValueOnce([row('A', null), row('B', 5)]);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000); // 较新的请求：已换成 B
    });
    expect(result.current).toBe('B');

    await act(async () => {
      releaseSlow([row('A', 5), row('B', null)]); // 迟到的旧快照
    });
    expect(result.current).toBe('B');
  });
});
