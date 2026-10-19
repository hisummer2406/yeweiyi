// Time range and paging for the 报警记录 / 出入库记录 tabs of device-detail. Pure (no `wx`), so it is
// checked in plain Node: `npm run check`.
//
// What the cloud gives us (yeweiyi-cloud internal/api/listparams.go, alarms.go, stock.go):
//   - beginTime / endTime are RFC3339; endTime is exclusive. Without them the window is the last 24h.
//   - limit is 1..500; offset is a plain row offset (both lists are served from the business DB).
//   - the window never starts before the device was assigned to the caller's customer.
//   - /api/alarms is a bare array with no "more" flag; /api/oil-in-out says `truncated`.

export type RangeKey = '24h' | '7d' | '30d' | 'custom';

export interface RecordsRange {
  key: RangeKey;
  // Local calendar days, 'YYYY-MM-DD'; only read when key === 'custom'.
  startDate?: string;
  endDate?: string;
}

// `labelKey` is an i18n key (locales/*.ts, `range`): this module is pure.
export const RANGE_PRESETS: { key: Exclude<RangeKey, 'custom'>; labelKey: string; hours: number }[] = [
  { key: '24h', labelKey: 'range.last24h', hours: 24 },
  { key: '7d', labelKey: 'range.last7d', hours: 24 * 7 },
  { key: '30d', labelKey: 'range.last30d', hours: 24 * 30 },
];

export interface QueryWindow {
  beginTime: string;
  endTime: string;
}

// RFC3339 in UTC without milliseconds.
function rfc3339(d: Date): string {
  return d.toISOString().replace(/\.\d{3}Z$/, 'Z');
}

// Strict 'YYYY-MM-DD' -> local midnight, or null when it is not a real calendar day.
function parseLocalDay(s: string | undefined): Date | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s || '');
  if (!m) return null;
  const y = Number(m[1]);
  const mo = Number(m[2]);
  const d = Number(m[3]);
  const date = new Date(y, mo - 1, d);
  return date.getFullYear() === y && date.getMonth() === mo - 1 && date.getDate() === d ? date : null;
}

export function formatDate(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

// '' when the range can be queried; otherwise why not. 'incomplete' just means the user has not picked
// both dates yet, so the page waits instead of complaining.
export type RangeProblem = '' | 'incomplete' | 'invalid' | 'order' | 'future';

export function customRangeProblem(range: RecordsRange, now: Date): RangeProblem {
  if (range.key !== 'custom') return '';
  if (!range.startDate || !range.endDate) return 'incomplete';
  const start = parseLocalDay(range.startDate);
  const end = parseLocalDay(range.endDate);
  if (!start || !end) return 'invalid';
  if (start.getTime() > end.getTime()) return 'order';
  if (start.getTime() > now.getTime()) return 'future';
  return '';
}

// The window to send, or null when the range cannot be queried yet. Presets roll back from `now`.
// A custom range covers whole local days: from 00:00 of the start day to 00:00 after the end day,
// clamped to `now` so "up to today" does not ask for the future.
export function resolveWindow(range: RecordsRange, now: Date): QueryWindow | null {
  if (range.key !== 'custom') {
    const preset = RANGE_PRESETS.find((p) => p.key === range.key);
    if (!preset) return null;
    return {
      beginTime: rfc3339(new Date(now.getTime() - preset.hours * 3600 * 1000)),
      endTime: rfc3339(now),
    };
  }
  if (customRangeProblem(range, now) !== '') return null;
  const start = parseLocalDay(range.startDate) as Date;
  const end = parseLocalDay(range.endDate) as Date;
  const endExclusive = new Date(end.getFullYear(), end.getMonth(), end.getDate() + 1);
  return {
    beginTime: rfc3339(start),
    endTime: rfc3339(endExclusive.getTime() > now.getTime() ? now : endExclusive),
  };
}

export const PAGE_SIZE = 50;
// A direction filter (入库/出库) is applied in the app because the cloud has no such filter, so a page
// of 50 movements may hold few or none of the wanted direction. Ask for bigger pages then.
export const FILTERED_PAGE_SIZE = 100;
// After a filtered page, keep fetching on its own until this many rows were added in this run...
export const MIN_ADDED_AFTER_FILTER = 20;
// ...but never more than this many requests per run; the user scrolling again continues.
export const MAX_AUTO_PAGES = 5;

// A type alias, not an interface: request.get() takes Record<string, unknown>, which an interface
// (no index signature) is not assignable to.
export type HistoryQuery = {
  device: string;
  beginTime: string;
  endTime: string;
  limit: number;
  offset: number;
  sort: false;
};

// /api/alarms has no "more" flag, so ask for one extra row as a probe. The offset steps by the page
// size (rows consumed), not by the rows shown, so one row the cloud could not decode cannot shift
// the next page.
export function alarmQuery(device: string, win: QueryWindow, pageIndex: number): HistoryQuery {
  return { device, ...win, limit: PAGE_SIZE + 1, offset: pageIndex * PAGE_SIZE, sort: false };
}

// "More" is a full page (>= PAGE_SIZE rows), not just the probe row: the cloud applies LIMIT in the
// database and then silently skips a row whose payload it can no longer parse, so a request for 51 rows
// can come back with 50 although more exist. Treating that as "more" costs at most one extra, empty
// request at an exact page boundary; the row that was then returned twice is dropped by the caller.
// This covers one skipped row per page; two or more in the same page would still read as the end.
export function splitAlarmPage<T>(rows: T[]): { items: T[]; hasMore: boolean } {
  return { items: rows.slice(0, PAGE_SIZE), hasMore: rows.length >= PAGE_SIZE };
}

// /api/oil-in-out reports `truncated`, so no probe row is needed.
export function stockQuery(device: string, win: QueryWindow, pageIndex: number, filtered: boolean): HistoryQuery {
  const size = filtered ? FILTERED_PAGE_SIZE : PAGE_SIZE;
  return { device, ...win, limit: size, offset: pageIndex * size, sort: false };
}

export function shouldAutoContinue(
  filtered: boolean,
  addedVisible: number,
  hasMore: boolean,
  pagesThisRun: number,
): boolean {
  return filtered && hasMore && addedVisible < MIN_ADDED_AFTER_FILTER && pagesThisRun < MAX_AUTO_PAGES;
}
