import { useCallback, useEffect, useRef, useState } from 'react';
import {
  defaultWindow,
  type RecordsFilter,
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
  /** device、窗口或筛选变化时自增 */
  version: number;
  /** 当前页（从 1 起）；device、窗口或筛选变了回到第 1 页 */
  page: number;
  setPage: (page: number) => void;
};

/**
 * 记录页的「设备 + 窗口 + 筛选 + 页码 + 轮询」状态。分页在云端做：每页一次请求（limit/offset），
 * 轮询刷新的是当前页；翻页时保留上一页的行直到新页到达（refreshing），不闪骨架屏。筛选（罐号、方向）交给云端先筛选后分页（#84），
 * 筛选变了与换设备一样清掉旧数据重新加载。
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
  filter: RecordsFilter = {},
): RecordsQueryState<T> {
  const { tank, direction } = filter;
  const [data, setData] = useState<T>();
  const [loading, setLoading] = useState(!!device);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<unknown>();
  const [range, setRange] = useState<RecordsWindow>();
  const [version, setVersion] = useState(0);
  const [owner, setOwner] = useState(device);
  // 页码属于一组「设备 + 窗口 + 筛选」：这组变了，页码自动回到 1（不必等 effect 清）
  const scope = `${device ?? ''}|${range?.begin.getTime() ?? ''}|${range?.end.getTime() ?? ''}|${tank ?? ''}|${direction ?? ''}`;
  const [paging, setPaging] = useState({ scope, page: 1 });
  const page = paging.scope === scope ? paging.page : 1;
  const lastScope = useRef<string | undefined>(undefined);
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
          toRecordsQuery(
            device,
            range ?? defaultWindow(),
            { tank, direction },
            page,
          ),
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
    [device, range, tank, direction, page],
  );

  // device、窗口或筛选变化：丢掉旧数据重新加载；只换了页：保留旧行，后台取新页。
  useEffect(() => {
    const fresh = lastScope.current !== scope;
    lastScope.current = scope;
    if (fresh) setVersion((v) => v + 1);
    void run(fresh);
    const timer = setInterval(() => void run(false), intervalMs);
    return () => {
      clearInterval(timer);
      seq.current++; // 卸载或参数变化后丢弃在途响应
    };
  }, [run, intervalMs, scope]);

  const reload = useCallback(() => void run(false), [run]);
  const setWindow = useCallback((r?: RecordsWindow) => setRange(r), []);
  const setPage = useCallback(
    (n: number) => setPaging({ scope, page: Math.max(1, Math.floor(n)) }),
    [scope],
  );

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
    page,
    setPage,
  };
}
