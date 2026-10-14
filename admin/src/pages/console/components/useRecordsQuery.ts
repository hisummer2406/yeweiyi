import { useCallback, useEffect, useRef, useState } from 'react';
import {
  defaultWindow,
  type RecordsWindow,
  toRecordsQuery,
} from '@/utils/records';

/** 自动刷新间隔：与设备总览一致，遥测与记录都是快照而非实时推送。 */
export const REFRESH_MS = 30_000;

export type RecordsQueryState<T> = {
  data?: T;
  /** 第一次加载（还没有数据）；后台刷新不算 */
  loading: boolean;
  /** 后台刷新或手动刷新进行中 */
  refreshing: boolean;
  /** 最近一次请求的失败；成功后清空。有 data 时说明显示的是上一次成功的结果 */
  error?: unknown;
  reload: () => void;
  /** 用户选的固定窗口；undefined 表示滚动的「最近 24 小时」 */
  range?: RecordsWindow;
  setRange: (r?: RecordsWindow) => void;
  /** device 或窗口变化时自增，调用方据此重置分页 */
  version: number;
};

/**
 * 记录页的「设备 + 窗口 + 轮询」状态。
 * - 没有 device 就不请求；
 * - 默认窗口每次请求按当前时间重新计算（滚动的最近 24 小时），用户选了范围就固定不动；
 * - 请求序号：device/窗口变了、重复刷新、卸载之后，迟到的旧响应一律丢弃；
 * - device 或窗口变化时清掉旧数据（别把上一台设备的记录挂在新设备下面），后台刷新则保留旧数据不闪；
 * - 状态记下它属于哪台设备（owner）：device 变了的那一帧（effect 还没来得及清）也不对外暴露旧设备的
 *   数据、错误，而是表现为「正在加载」，不会短暂地把 A 的记录挂在 B 下面。
 */
export function useRecordsQuery<T>(
  fetcher: (q: API.RecordsQuery) => Promise<T>,
  device: string | undefined,
  intervalMs: number = REFRESH_MS,
): RecordsQueryState<T> {
  const [data, setData] = useState<T>();
  const [loading, setLoading] = useState(!!device);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<unknown>();
  const [range, setRange] = useState<RecordsWindow>();
  const [version, setVersion] = useState(0);
  const [owner, setOwner] = useState(device);
  const seq = useRef(0);
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;

  const run = useCallback(
    async (fresh: boolean) => {
      const mine = ++seq.current;
      setOwner(device);
      if (!device) {
        setData(undefined);
        setError(undefined);
        setLoading(false);
        setRefreshing(false);
        return;
      }
      if (fresh) {
        setData(undefined);
        setLoading(true);
      }
      setRefreshing(true);
      try {
        const res = await fetcherRef.current(
          toRecordsQuery(device, range ?? defaultWindow()),
        );
        if (mine !== seq.current) return;
        setData(res);
        setError(undefined);
      } catch (e) {
        if (mine !== seq.current) return;
        setError(e);
      } finally {
        if (mine === seq.current) {
          setLoading(false);
          setRefreshing(false);
        }
      }
    },
    [device, range],
  );

  // device 或窗口变化：丢掉旧数据，重新加载，并通知调用方重置分页。
  useEffect(() => {
    setVersion((v) => v + 1);
    void run(true);
    const timer = setInterval(() => void run(false), intervalMs);
    return () => {
      clearInterval(timer);
      seq.current++; // 卸载或参数变化后丢弃在途响应
    };
  }, [run, intervalMs]);

  const reload = useCallback(() => void run(false), [run]);
  const setWindow = useCallback((r?: RecordsWindow) => setRange(r), []);

  // 状态属于另一台设备（换设备后的第一帧）：当作还没加载，等 effect 清空并发起新请求
  const current = owner === device;
  return {
    data: current ? data : undefined,
    loading: current ? loading : !!device,
    refreshing: current ? refreshing : false,
    error: current ? error : undefined,
    reload,
    range,
    setRange: setWindow,
    version,
  };
}
