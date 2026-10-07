/**
 * 记录页（报警、出入库）的纯逻辑：查询窗口、方向过滤、降级与截断提示。
 * 放在这里是为了让「哪些情况要给用户什么提示」能不带 DOM 地单测。
 */

/** 云端单次上限（internal/api/listparams.go maxLimit）；前端一次取满，分页在前端做。 */
export const RECORDS_LIMIT = 500;

/** 默认窗口：最近 24 小时（与云端默认一致，但这里显式传，不依赖云端默认）。 */
export const DEFAULT_WINDOW_MS = 24 * 60 * 60 * 1000;

export type RecordsWindow = { begin: Date; end: Date };

/** 以 `now` 为终点的默认窗口。 */
export const defaultWindow = (now: Date = new Date()): RecordsWindow => ({
  begin: new Date(now.getTime() - DEFAULT_WINDOW_MS),
  end: now,
});

/** 窗口转成云端要求的 RFC3339（UTC）查询参数。 */
export const toRecordsQuery = (
  device: string,
  w: RecordsWindow,
): API.RecordsQuery => ({
  device,
  beginTime: w.begin.toISOString(),
  endTime: w.end.toISOString(),
});

/** 出入库方向过滤（云端没有方向过滤）；direction 缺省则不过滤。 */
export const filterByDirection = (
  rows: API.StockEvent[],
  direction?: 'IN' | 'OUT',
): API.StockEvent[] =>
  direction ? rows.filter((r) => r.direction === direction) : rows;

/**
 * 一条要展示给用户的提示。组件按 id 取文案（pages.console.records.notice.<id>），values 填占位符。
 * error 与 warning 表示「这份列表不能当作完整、可信」，info 只是说明。
 */
export type Notice = {
  level: 'error' | 'warning' | 'info';
  id:
    | 'partial'
    | 'truncated'
    | 'legacySkipped'
    | 'invalidSource'
    | 'folded'
    | 'firmware';
  values: Record<string, string>;
};

/** 是否有不是纯说明的提示：有的话不能把空列表显示成「没有记录」。 */
export const hasDegradation = (notices: Notice[]): boolean =>
  notices.some((n) => n.level !== 'info');

const limitValue = { limit: String(RECORDS_LIMIT) };

/** 报警：响应头 partial，以及满 500 条（云端没有 truncated 标记，满额只能当作可能被截断）。 */
export function summarizeAlarms(list: API.AlarmList): Notice[] {
  const out: Notice[] = [];
  if (list.partial) out.push({ level: 'warning', id: 'partial', values: {} });
  if (list.rows.length >= RECORDS_LIMIT) {
    out.push({ level: 'warning', id: 'truncated', values: limitValue });
  }
  return out;
}

/**
 * 出入库：502 降级或 unparsable>0 是错误级（设备固件未升级，部分记录读不到）；
 * partial、legacySkipped、invalidSource、truncated 是警告级；folded 只是说明。
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
  if (list.truncated) {
    out.push({ level: 'warning', id: 'truncated', values: limitValue });
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
