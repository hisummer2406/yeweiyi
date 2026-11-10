import { useCallback, useEffect, useRef, useState } from 'react';
import { getTankSettings } from '@/services/ant-design-pro/deviceConfig';
import { decodeToken, getToken } from '@/utils/auth';
import { mapLimit, TELEMETRY_CONCURRENCY } from '@/utils/overviewPaging';
import {
  emptyTankShapes,
  type TankShapeState,
  tankShapeFailure,
} from '@/utils/tankShape';

export const SHAPE_REFRESH_MS = 60_000;
const account = () => decodeToken(getToken())?.userId;

/** 只读当前可见设备；与遥测独立刷新，配置失败不会挡住读数。 */
export function useTankShapes(devices: string[]) {
  const key = JSON.stringify([account(), devices]);
  const [scope, setScope] = useState<{
    key: string;
    states: Record<string, TankShapeState>;
  }>({ key: '', states: {} });
  const refreshRef = useRef<() => void>(() => {});

  useEffect(() => {
    const [userId, names] = JSON.parse(key) as [number | null, string[]];
    let alive = true;
    let generation = 0;
    const states: Record<string, TankShapeState> = {};
    const current = (mine: number) =>
      alive &&
      !document.hidden &&
      mine === generation &&
      (account() ?? null) === userId;
    const publish = () => setScope({ key, states: { ...states } });
    const refresh = async () => {
      if (document.hidden) return;
      const mine = ++generation;
      await mapLimit(names, TELEMETRY_CONCURRENCY, async (device) => {
        // 翻页／隐藏后，已排队但尚未发出的旧设备请求也要停止。
        if (!current(mine)) return;
        try {
          const rows = await getTankSettings(device);
          if (!current(mine)) return;
          states[device] = { rows, status: 'ok' };
        } catch (error) {
          if (!current(mine)) return;
          states[device] = tankShapeFailure(
            states[device] ?? emptyTankShapes(),
            (error as { status?: number }).status ?? 0,
          );
        }
        publish();
      });
    };
    refreshRef.current = () => void refresh();
    void refresh();
    const timer = setInterval(() => void refresh(), SHAPE_REFRESH_MS);
    const visible = () => {
      if (!document.hidden) void refresh();
    };
    document.addEventListener('visibilitychange', visible);
    return () => {
      alive = false;
      clearInterval(timer);
      document.removeEventListener('visibilitychange', visible);
    };
  }, [key]);

  return {
    states: scope.key === key ? scope.states : {},
    refresh: useCallback(() => refreshRef.current(), []),
  };
}
