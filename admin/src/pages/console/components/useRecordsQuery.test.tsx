import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { REFRESH_MS, useRecordsQuery } from './useRecordsQuery';

type Q = API.RecordsQuery;
const deferred = <T,>() => {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
};

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('useRecordsQuery', () => {
  it('does not request without a device', async () => {
    const fetcher = vi.fn();
    const { result } = renderHook(() => useRecordsQuery(fetcher, undefined));
    await act(async () => {});
    expect(fetcher).not.toHaveBeenCalled();
    expect(result.current.loading).toBe(false);
    expect(result.current.data).toBeUndefined();
  });

  it('requests the last 24 hours ending now for the device', async () => {
    const fetcher = vi.fn().mockResolvedValue('ok');
    const { result } = renderHook(() => useRecordsQuery(fetcher, 'D1'));
    await waitFor(() => expect(result.current.data).toBe('ok'));
    const q: Q = fetcher.mock.calls[0][0];
    expect(q.device).toBe('D1');
    const span = Date.parse(q.endTime) - Date.parse(q.beginTime);
    expect(span).toBe(24 * 60 * 60 * 1000);
    expect(Math.abs(Date.parse(q.endTime) - Date.now())).toBeLessThan(5000);
  });

  it('a user-chosen range is sent as is and does not roll', async () => {
    const fetcher = vi.fn().mockResolvedValue('ok');
    const { result } = renderHook(() => useRecordsQuery(fetcher, 'D1'));
    await waitFor(() => expect(result.current.data).toBe('ok'));
    const range = {
      begin: new Date('2026-01-01T00:00:00Z'),
      end: new Date('2026-01-02T00:00:00Z'),
    };
    act(() => result.current.setRange(range));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    expect(fetcher.mock.calls[1][0]).toEqual({
      device: 'D1',
      beginTime: '2026-01-01T00:00:00.000Z',
      endTime: '2026-01-02T00:00:00.000Z',
      limit: 20,
      offset: 0,
    });
  });

  it('reloads every 30 seconds without blanking the data', async () => {
    vi.useFakeTimers();
    const fetcher = vi.fn().mockResolvedValue('ok');
    const { result } = renderHook(() => useRecordsQuery(fetcher, 'D1'));
    await act(async () => {});
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(result.current.data).toBe('ok');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(REFRESH_MS);
    });
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(result.current.data).toBe('ok');
    expect(result.current.loading).toBe(false);
  });

  it('stops polling after unmount', async () => {
    vi.useFakeTimers();
    const fetcher = vi.fn().mockResolvedValue('ok');
    const { unmount } = renderHook(() => useRecordsQuery(fetcher, 'D1'));
    await act(async () => {});
    unmount();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(REFRESH_MS * 3);
    });
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('drops a late response from the previous device and clears its data', async () => {
    const first = deferred<string>();
    const fetcher = vi.fn((q: Q) =>
      q.device === 'D1' ? first.promise : Promise.resolve('data-of-D2'),
    );
    const { result, rerender } = renderHook(
      ({ d }) => useRecordsQuery(fetcher, d),
      { initialProps: { d: 'D1' } },
    );
    rerender({ d: 'D2' });
    await waitFor(() => expect(result.current.data).toBe('data-of-D2'));
    await act(async () => first.resolve('late-data-of-D1'));
    expect(result.current.data).toBe('data-of-D2');
  });

  it('clears the previous device data while the new one loads', async () => {
    const second = deferred<string>();
    const fetcher = vi.fn((q: Q) =>
      q.device === 'D1' ? Promise.resolve('data-of-D1') : second.promise,
    );
    const { result, rerender } = renderHook(
      ({ d }) => useRecordsQuery(fetcher, d),
      { initialProps: { d: 'D1' } },
    );
    await waitFor(() => expect(result.current.data).toBe('data-of-D1'));
    rerender({ d: 'D2' });
    await waitFor(() => expect(result.current.loading).toBe(true));
    expect(result.current.data).toBeUndefined();
    await act(async () => second.resolve('data-of-D2'));
  });

  it('a failure surfaces as error and is not turned into empty data', async () => {
    const boom = new Error('boom');
    const fetcher = vi.fn().mockRejectedValue(boom);
    const { result } = renderHook(() => useRecordsQuery(fetcher, 'D1'));
    await waitFor(() => expect(result.current.error).toBe(boom));
    expect(result.current.data).toBeUndefined();
    expect(result.current.loading).toBe(false);
  });

  it('keeps the last good data when a background refresh fails, and clears the error on recovery', async () => {
    vi.useFakeTimers();
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce('good')
      .mockRejectedValueOnce(new Error('later'))
      .mockResolvedValueOnce('good2');
    const { result } = renderHook(() => useRecordsQuery(fetcher, 'D1'));
    await act(async () => {});
    await act(async () => {
      await vi.advanceTimersByTimeAsync(REFRESH_MS);
    });
    expect(result.current.data).toBe('good');
    expect(result.current.error).toBeInstanceOf(Error);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(REFRESH_MS);
    });
    expect(result.current.data).toBe('good2');
    expect(result.current.error).toBeUndefined();
  });

  it('bumps version when the device or window changes, not on a refresh', async () => {
    vi.useFakeTimers();
    const fetcher = vi.fn().mockResolvedValue('ok');
    const { result, rerender } = renderHook(
      ({ d }) => useRecordsQuery(fetcher, d),
      { initialProps: { d: 'D1' } },
    );
    await act(async () => {});
    const v0 = result.current.version;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(REFRESH_MS);
    });
    expect(result.current.version).toBe(v0);
    rerender({ d: 'D2' });
    await act(async () => {});
    expect(result.current.version).toBeGreaterThan(v0);
    const v1 = result.current.version;
    act(() =>
      result.current.setRange({ begin: new Date(0), end: new Date(1000) }),
    );
    await act(async () => {});
    expect(result.current.version).toBeGreaterThan(v1);
  });

  it('manual reload requests again with the same parameters', async () => {
    const fetcher = vi.fn().mockResolvedValue('ok');
    const { result } = renderHook(() => useRecordsQuery(fetcher, 'D1'));
    await waitFor(() => expect(result.current.data).toBe('ok'));
    act(() => result.current.reload());
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    expect(fetcher.mock.calls[1][0].device).toBe('D1');
  });
  it("never exposes the previous device's data under a new device, even on the first frame", async () => {
    const hold = deferred<string>();
    const fetcher = vi.fn((q: Q) =>
      q.device === 'B' ? hold.promise : Promise.resolve(`rows-of-${q.device}`),
    );
    // 记录 device === 'B' 的每一次渲染：effect 清空旧数据之前的那一帧也不能带着 A 的数据
    const framesOfB: Array<{ data?: string; loading: boolean }> = [];
    const { result, rerender } = renderHook(
      ({ device }) => {
        const r = useRecordsQuery<string>(fetcher, device);
        if (device === 'B')
          framesOfB.push({ data: r.data, loading: r.loading });
        return r;
      },
      { initialProps: { device: 'A' } },
    );
    await waitFor(() => expect(result.current.data).toBe('rows-of-A'));
    rerender({ device: 'B' });
    await act(async () => hold.resolve('rows-of-B'));
    expect(framesOfB.length).toBeGreaterThan(0);
    expect(framesOfB[0]).toEqual({ data: undefined, loading: true });
    expect(framesOfB.some((f) => f.data === 'rows-of-A')).toBe(false);
    expect(result.current.data).toBe('rows-of-B');
  });

  it("drops a late response of the old device so it cannot overwrite the new device's state", async () => {
    const a = deferred<string>();
    const b = deferred<string>();
    const fetcher = vi.fn((q: Q) => (q.device === 'A' ? a.promise : b.promise));
    const { result, rerender } = renderHook(
      ({ device }) => useRecordsQuery(fetcher, device),
      { initialProps: { device: 'A' } },
    );
    await act(async () => {});
    rerender({ device: 'B' });
    await act(async () => {});
    await act(async () => b.resolve('rows-of-B'));
    expect(result.current.data).toBe('rows-of-B');
    // A 的响应迟到：既不能改数据，也不能让错误/加载态回到 B 的页面
    await act(async () => a.resolve('rows-of-A'));
    expect(result.current.data).toBe('rows-of-B');
    expect(result.current.loading).toBe(false);
  });

  it('a new device with no data shows empty data, not the old cache', async () => {
    const fetcher = vi.fn((q: Q) =>
      Promise.resolve(q.device === 'A' ? ['a1'] : []),
    );
    const { result, rerender } = renderHook(
      ({ device }) => useRecordsQuery<string[]>(fetcher, device),
      { initialProps: { device: 'A' } },
    );
    await waitFor(() => expect(result.current.data).toEqual(['a1']));
    rerender({ device: 'B' });
    await waitFor(() => expect(result.current.data).toEqual([]));
  });

  it('a page flip asks for the next offset and keeps the old rows until it arrives', async () => {
    const next = deferred<string>();
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce('page1')
      .mockReturnValueOnce(next.promise);
    const { result } = renderHook(() => useRecordsQuery(fetcher, 'D1'));
    await waitFor(() => expect(result.current.data).toBe('page1'));
    act(() => result.current.setPage(2));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    expect(fetcher.mock.calls[1][0]).toMatchObject({ limit: 20, offset: 20 });
    expect(result.current.page).toBe(2);
    expect(result.current.loading).toBe(false);
    expect(result.current.refreshing).toBe(true);
    expect(result.current.data).toBe('page1');
    await act(async () => next.resolve('page2'));
    expect(result.current.data).toBe('page2');
  });

  it('changing the device or the filter goes back to page 1', async () => {
    const fetcher = vi.fn().mockResolvedValue('ok');
    const { result, rerender } = renderHook(
      ({ device, tank }: { device: string; tank?: number }) =>
        useRecordsQuery(fetcher, device, REFRESH_MS, { tank }),
      { initialProps: { device: 'D1' } as { device: string; tank?: number } },
    );
    await waitFor(() => expect(result.current.data).toBe('ok'));
    act(() => result.current.setPage(3));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    rerender({ device: 'D1', tank: 2 });
    expect(result.current.page).toBe(1);
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(3));
    expect(fetcher.mock.calls[2][0]).toMatchObject({ tank: 2, offset: 0 });
    act(() => result.current.setPage(2));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(4));
    rerender({ device: 'D2', tank: 2 });
    expect(result.current.page).toBe(1);
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(5));
    expect(fetcher.mock.calls[4][0]).toMatchObject({ device: 'D2', offset: 0 });
  });
});
