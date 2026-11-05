/**
 * 记录页（报警、出入库）的纯逻辑：查询窗口、方向过滤、降级与截断提示。
 * 放在这里是为了让「哪些情况要给用户什么提示」能不带 DOM 地单测。
 */

/** 缺失值的显示：云端没给的值一律「—」，不显示 0（0 本身是有效值照常显示）。 */
export const DASH = '—';

/** 记录表里的数值：最多两位小数（同小程序 recordColumns.num），缺失或非有限数显示「—」。 */
export const num = (v: unknown): string =>
  typeof v === 'number' && Number.isFinite(v)
    ? String(Math.round((v + Number.EPSILON) * 100) / 100)
    : DASH;

/**
 * 每页条数。分页在云端做（limit/offset，internal/api/listparams.go）：每翻一页请求一次，
 * 云端多取一条判断后面还有没有（出入库、交班、库存的 truncated；报警由前端多要一条自己判断）。
 * 云端不给总数，所以只有上一页/下一页。
 */
export const PAGE_SIZE = 20;

/** 默认窗口：最近 24 小时（与云端默认一致，但这里显式传，不依赖云端默认）。 */
export const DEFAULT_WINDOW_MS = 24 * 60 * 60 * 1000;

export type RecordsWindow = { begin: Date; end: Date };

/** 以 `now` 为终点的默认窗口。 */
export const defaultWindow = (now: Date = new Date()): RecordsWindow => ({
  begin: new Date(now.getTime() - DEFAULT_WINDOW_MS),
  end: now,
});

/** 罐号与方向筛选（yeweiyi-cloud docs/records-filter-contract.md，#84）；缺省的字段不筛选。 */
export type RecordsFilter = { tank?: number; direction?: 'IN' | 'OUT' };

/** 窗口转成云端要求的 RFC3339（UTC）查询参数，第 page 页（从 1 起）；筛选只带有值的字段。 */
export const toRecordsQuery = (
  device: string,
  w: RecordsWindow,
  filter: RecordsFilter = {},
  page = 1,
): API.RecordsQuery => ({
  device,
  beginTime: w.begin.toISOString(),
  endTime: w.end.toISOString(),
  limit: PAGE_SIZE,
  offset: (Math.max(1, Math.floor(page)) - 1) * PAGE_SIZE,
  ...(filter.tank !== undefined ? { tank: filter.tank } : {}),
  ...(filter.direction ? { direction: filter.direction } : {}),
});

/**
 * 按行再过滤一遍方向与罐号。云端已按 tank/direction 先筛选后分页；这一步只是兜底：
 * 还没有 #84 的旧云端会忽略这两个参数、返回整机记录，此时不能把别的罐或另一方向显示出来。
 */
export const filterRows = <R extends { tank: number; direction?: string }>(
  rows: R[],
  filter: RecordsFilter,
): R[] =>
  rows.filter(
    (r) =>
      (filter.tank === undefined || r.tank === filter.tank) &&
      (!filter.direction || r.direction === filter.direction),
  );

/** 出入库方向过滤；direction 缺省则不过滤。 */
export const filterByDirection = (
  rows: API.StockEvent[],
  direction?: 'IN' | 'OUT',
): API.StockEvent[] => filterRows(rows, { direction });

/**
 * 一条要展示给用户的提示。组件按 id 取文案（pages.console.records.notice.<id>），values 填占位符。
 * error 与 warning 表示「这份列表不能当作完整、可信」，info 只是说明。
 */
export type Notice = {
  level: 'error' | 'warning' | 'info';
  id:
    | 'partial'
    | 'legacySkipped'
    | 'invalidSource'
    | 'folded'
    | 'firmware'
    | 'unsupported'
    | 'syncing'
    | 'syncUnknown'
    | 'unparsable';
  values: Record<string, string>;
};

/** 是否有不是纯说明的提示：有的话不能把空列表显示成「没有记录」。 */
export const hasDegradation = (notices: Notice[]): boolean =>
  notices.some((n) => n.level !== 'info');

/** 报警：响应头 partial（首次回补未完成）。 */
export function summarizeAlarms(list: API.AlarmList): Notice[] {
  const out: Notice[] = [];
  if (list.partial) out.push({ level: 'warning', id: 'partial', values: {} });
  return out;
}

/**
 * 出入库：502 降级或 unparsable>0 是错误级（设备固件未升级，部分记录读不到）；
 * partial、legacySkipped、invalidSource 是警告级；folded 只是说明。truncated 是「后面还有」，由翻页处理，不是提示。
 */
export function summarizeStock(list: API.StockList): Notice[] {
  const out: Notice[] = [];
  if (list.degraded || list.unparsable > 0) {
    out.push({
      level: 'error',
      id: 'firmware',
      values: {
        count: String(list.unparsable),
        device: list.degraded?.device ?? '',
        sample: list.degraded?.sample ?? '',
      },
    });
  }
  if (list.partial) out.push({ level: 'warning', id: 'partial', values: {} });
  if (list.legacySkipped > 0) {
    out.push({
      level: 'warning',
      id: 'legacySkipped',
      values: { count: String(list.legacySkipped) },
    });
  }
  if (list.invalidSource > 0) {
    out.push({
      level: 'warning',
      id: 'invalidSource',
      values: { count: String(list.invalidSource) },
    });
  }
  if (list.folded > 0) {
    out.push({
      level: 'info',
      id: 'folded',
      values: { count: String(list.folded) },
    });
  }
  return out;
}

/**
 * 交班/库存记录（cloud#85 §4）的完整性提示，规则与小程序 recordColumns.syncSummary 一致：
 * - UNSUPPORTED：设备从未上报过这类记录，显示「设备暂不支持」而不是「暂无记录」；
 * - SYNCING：初次回补未完成，列表为空也不能说没有记录；
 * - 不认识的值或缺失（更新/更旧的云端）：无法确认是否完整；
 * - 只有 SYNCED 才可能显示「暂无记录」（SYNCED 但 partial 仍按同步中提示）。
 * unparsable 是整机计数，按罐筛选时也不能说成「该罐有 N 条错误」。这些都是警告级：有它们时空列表不显示成「没有记录」。
 * truncated 是「后面还有」，由翻页处理。
 */
export function summarizeSynced(list: API.SyncedRecordList<unknown>): Notice[] {
  const out: Notice[] = [];
  const status = list.syncStatus;
  if (status === 'UNSUPPORTED') {
    out.push({ level: 'warning', id: 'unsupported', values: {} });
  } else if (status === 'SYNCING') {
    out.push({ level: 'warning', id: 'syncing', values: {} });
  } else if (status !== 'SYNCED') {
    out.push({ level: 'warning', id: 'syncUnknown', values: {} });
  } else if (list.partial) {
    out.push({ level: 'warning', id: 'syncing', values: {} });
  }
  if (list.unparsable > 0) {
    out.push({
      level: 'warning',
      id: 'unparsable',
      values: { count: String(list.unparsable) },
    });
  }
  return out;
}

/**
 * 按所属记录分组的斑马底色：同一班（shiftId）或同一次库存（recordId）的多行同底色，相邻两组交替。
 * 返回与 rows 等长的 0/1 序列；组键为空的行各自成组。
 */
export function groupStripes<R>(rows: R[], keyOf: (r: R) => string): number[] {
  const out: number[] = [];
  let prev: string | undefined;
  let stripe = 1;
  rows.forEach((r, i) => {
    const k = keyOf(r);
    if (i === 0 || !k || k !== prev) stripe ^= 1;
    prev = k;
    out.push(stripe);
  });
  return out;
}
