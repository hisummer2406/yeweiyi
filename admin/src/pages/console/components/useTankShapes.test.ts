import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { tankShapeOf } from '@/utils/tankShape';

const getTankSettings = vi.hoisted(() => vi.fn());
let userId = 1;
vi.mock('@/services/ant-design-pro/deviceConfig', () => ({ getTankSettings }));
vi.mock('@/utils/auth', () => ({
  getToken: () => 'session',
  decodeToken: () => ({ userId }),
}));

import { SHAPE_REFRESH_MS, useTankShapes } from './useTankShapes';

const rows = (tankShape: number | null) => [
  { tank: 3, tankShape, reportedAt: '2026-01-01T00:00:00Z' },
];
beforeEach(() => {
  userId = 1;
  getTankSettings.mockReset().mockResolvedValue(rows(1));
  Object.defineProperty(document, 'hidden', {
    value: false,
    configurable: true,
  });
});

describe('罐型配置独立读取', () => {
  it('翻页后不再派发旧页面排队中的配置请求', async () => {
    const pending: Array<(v: ReturnType<typeof rows>) => void> = [];
    getTankSettings.mockImplementation((name) =>
      name === 'new'
        ? Promise.resolve(rows(0))
        : new Promise((r) => {
            pending.push(r);
          }),
    );
    const { rerender } = renderHook(({ devices }) => useTankShapes(devices), {
      initialProps: {
        devices: ['old0', 'old1', 'old2', 'old3', 'old4', 'old5'],
      },
    });
    expect(getTankSettings).toHaveBeenCalledTimes(5);
    rerender({ devices: ['new'] });
    await act(async () => {
      for (const resolve of pending) resolve(rows(1));
    });
    expect(getTankSettings.mock.calls.map((c) => c[0])).not.toContain('old5');
  });

  it('按实际罐号匹配；缺失或无法识别按卧罐', () => {
    for (const [value, expected] of [
      [0, 'horizontal'],
      [1, 'vertical'],
      [null, 'horizontal'],
      [2, 'horizontal'],
    ] as const) {
      const state = { rows: rows(value), status: 'ok' as const };
      expect(tankShapeOf(state, 3).shape).toBe(expected);
      expect(tankShapeOf(state, 1).shape).toBe('horizontal');
    }
  });

  it('一设备一次请求，切换可见设备后丢弃旧响应', async () => {
    let resolveOld!: (v: ReturnType<typeof rows>) => void;
    getTankSettings.mockImplementation((name) =>
      name === 'old'
        ? new Promise((r) => {
            resolveOld = r;
          })
        : Promise.resolve(rows(0)),
    );
    const { result, rerender } = renderHook(
      ({ devices }) => useTankShapes(devices),
      { initialProps: { devices: ['old'] } },
    );
    rerender({ devices: ['new'] });
    await act(async () => {});
    await act(async () => resolveOld(rows(1)));
    expect(Object.keys(result.current.states)).toEqual(['new']);
    expect(tankShapeOf(result.current.states.new, 3).shape).toBe('horizontal');
    expect(getTankSettings.mock.calls.map((c) => c[0])).toEqual(['old', 'new']);
  });

  it('首次失败不阻塞其它设备；临时失败标旧值，403 清理缓存', async () => {
    const { result } = renderHook(() => useTankShapes(['A', 'B']));
    await act(async () => {});
    getTankSettings.mockRejectedValue({ status: 503 });
    await act(async () => {
      result.current.refresh();
    });
    expect(result.current.states.A.status).toBe('stale');
    expect(tankShapeOf(result.current.states.A, 3).shape).toBe('vertical');
    getTankSettings.mockRejectedValue({ status: 403 });
    await act(async () => {
      result.current.refresh();
    });
    expect(result.current.states.A).toEqual({ rows: [], status: 'error' });
  });

  it('隐藏不轮询，恢复立即刷新，卸载停止请求', async () => {
    vi.useFakeTimers();
    const { unmount } = renderHook(() => useTankShapes(['A']));
    await act(async () => {});
    Object.defineProperty(document, 'hidden', {
      value: true,
      configurable: true,
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(SHAPE_REFRESH_MS);
    });
    expect(getTankSettings).toHaveBeenCalledTimes(1);
    Object.defineProperty(document, 'hidden', {
      value: false,
      configurable: true,
    });
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'));
    });
    expect(getTankSettings).toHaveBeenCalledTimes(2);
    unmount();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(SHAPE_REFRESH_MS);
    });
    expect(getTankSettings).toHaveBeenCalledTimes(2);
  });

  it('换账号后旧请求和旧罐型均不能进入新会话', async () => {
    let resolveOld!: (v: ReturnType<typeof rows>) => void;
    getTankSettings.mockImplementationOnce(
      () =>
        new Promise((r) => {
          resolveOld = r;
        }),
    );
    const { result, rerender } = renderHook(() => useTankShapes(['A']));
    userId = 2;
    rerender();
    await act(async () => {});
    await act(async () => resolveOld(rows(0)));
    expect(tankShapeOf(result.current.states.A, 3).shape).toBe('vertical');
  });
});
