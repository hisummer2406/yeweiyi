import { describeError } from './errors';
import { currentLocale, t } from './i18n';
import { ApiError, get } from './request';
import { formatLocalTime } from './format';
import { isOilInOutListResponse, summarizeOilInOut } from './oilInOut';
import { alarmRowKey, inventoryRowKey, isRecordListResponse, shiftRowKey, stockRowKey, syncSummary } from './recordColumns';
import {
  FILTERED_PAGE_SIZE,
  PAGE_SIZE,
  alarmQuery,
  filterParams,
  isFiltered,
  matchesFilter,
  shouldAutoContinue,
  splitAlarmPage,
  stockQuery,
  type QueryWindow,
  type RecordFilter,
} from './recordsRange';
import type { Alarm, OilInOutListResponse, OilInOutRow, RecordListResponse, ShiftRecordRow, StockRecordRow } from '../types/api';
import type { Msg } from './i18nCore';

// One paged history list — alarms (GET /api/alarms), stock in/out (GET /api/oil-in-out), shifts
// (GET /api/shift-records) or stock snapshots (GET /api/stock-records) — for one device, one time window
// and one filter. Shared by device-detail's 报警记录 tab and the 我的记录 list
// pages so the paging rules live in one place. The pure decisions are in recordsRange.ts.
//
// Tank and direction are sent to the cloud, which filters before paging (yeweiyi-cloud
// docs/records-filter-contract.md, #84). The rows are filtered here again for an older cloud that ignores
// the parameters: a page may then add few or no rows, so a run keeps fetching on its own (bounded,
// shouldAutoContinue), and only the cloud saying it has no more ends the list. A named tank the user may
// not see is 403 tank_not_authorized (onFailure), never an empty list.

// Bottom-of-list state: more = idle with more to load, loading, end = nothing further, error = the last
// page failed (tap to retry). '' = no footer.
export type Footer = '' | 'more' | 'loading' | 'end' | 'error';

// 'stock' = 出入库 movements; 'inventory' = 库存 snapshots.
export type FeedKind = 'alarm' | 'stock' | 'shift' | 'inventory';

export type FeedRow = Alarm | OilInOutRow | ShiftRecordRow | StockRecordRow;

const RECORD_PATHS: Record<'shift' | 'inventory', string> = {
  shift: '/api/shift-records',
  inventory: '/api/stock-records',
};

export interface FeedQuery {
  device: string;
  win: QueryWindow;
  filter: RecordFilter;
}

export interface FeedState<T> {
  rows: T[];
  // At least one page of the current query arrived (the list on screen belongs to it).
  loaded: boolean;
  loading: boolean;
  // First load failed, nothing to show: the reason (the page offers a retry).
  error: string;
  // A refresh failed while an earlier list is on screen: "may be out of date, last updated …".
  stale: string;
  // The list is not known to be whole (unparsable / legacySkipped / partial / invalidSource / syncing).
  note: string;
  footer: Footer;
  // An empty list may be shown as "no records". False while a 交班/库存 list is not SYNCED (cloud#85).
  canSayEmpty: boolean;
  // 交班/库存: the device has never reported this kind of record (syncStatus UNSUPPORTED).
  unsupported: boolean;
  // 交班/库存: the cloud has no such endpoint (a cloud before #85 answers the route with a bare 404).
  notOpen: boolean;
}

type Page<T> = { rows: T[]; hasMore: boolean; notes: Msg[]; canSayEmpty: boolean; unsupported: boolean; notOpen: boolean };

function emptyState<T>(): FeedState<T> {
  return {
    rows: [], loaded: false, loading: false, error: '', stale: '', note: '', footer: '',
    canSayEmpty: true, unsupported: false, notOpen: false,
  };
}

function plainPage<T>(rows: T[], hasMore: boolean, notes: Msg[]): Page<T> {
  return { rows, hasMore, notes, canSayEmpty: true, unsupported: false, notOpen: false };
}

// The cloud's machine code on a failed answer (e.g. "tank_not_authorized"), if any.
export function failureCode(err: unknown): string {
  const body = err instanceof ApiError ? err.body : undefined;
  const code = body && typeof body === 'object' ? (body as { code?: unknown }).code : undefined;
  return typeof code === 'string' ? code : '';
}

export class RecordFeed<T extends FeedRow> {
  state: FeedState<T> = emptyState<T>();
  // Bumped by every reload and reset: a run that no longer owns the seq drops what it gets.
  private seq = 0;
  // Only changed together with the list, so a failed refresh keeps paging the list on screen.
  private query: FeedQuery | null = null;
  private page = 0;
  private hasMore = false;
  // Held by a reload AND by a load-more for their whole run, so two runs never ask for the same page.
  private busy = false;
  private lastOk = '';
  // Pages that arrived in the current run (a run fetches several on its own when filtered).
  private runPages = 0;

  constructor(
    private kind: FeedKind,
    private onChange: (state: FeedState<T>) => void,
    // Called on every failed request before the list shows it, e.g. to drop a tank the user lost.
    private onFailure?: (err: Error) => void,
  ) {}

  private set(patch: Partial<FeedState<T>>) {
    this.state = { ...this.state, ...patch };
    this.onChange(this.state);
  }

  private key(row: T): string {
    switch (this.kind) {
      case 'alarm':
        return alarmRowKey(row as Alarm);
      case 'shift':
        return shiftRowKey(row as ShiftRecordRow);
      case 'inventory':
        return inventoryRowKey(row as StockRecordRow);
      default:
        return stockRowKey(row as OilInOutRow);
    }
  }

  // Pages of the list on screen (0 = nothing loaded yet).
  pageCount(): number {
    return this.page;
  }

  // Drops the list and everything in flight (device, tank, filter or window changed; page unloaded).
  reset() {
    this.seq++;
    this.query = null;
    this.page = 0;
    this.hasMore = false;
    this.busy = false;
    this.lastOk = '';
    this.set(emptyState<T>());
  }

  // Same query as the list on screen (a refresh keeps it until the new first page is in).
  sameQuery(q: FeedQuery): boolean {
    const c = this.query;
    return (
      !!c &&
      c.device === q.device &&
      c.filter.tank === q.filter.tank &&
      c.filter.direction === q.filter.direction &&
      c.win.beginTime === q.win.beginTime &&
      c.win.endTime === q.win.endTime
    );
  }

  private fetchPage(q: FeedQuery, pageIndex: number): Promise<Page<T>> {
    const filtered = isFiltered(q.filter);
    if (this.kind === 'alarm') {
      // A bare array, not an envelope (types/api.d.ts); no "more" flag, so one probe row past the page.
      const query = { ...alarmQuery(q.device, q.win, pageIndex, filtered), ...filterParams(q.filter, false) };
      return get<Alarm[]>('/api/alarms', query).then((rows) => {
        if (!Array.isArray(rows)) throw new Error('unexpected response shape from /api/alarms');
        const { items, hasMore } = splitAlarmPage(rows, filtered ? FILTERED_PAGE_SIZE : PAGE_SIZE);
        return plainPage(items.filter((a) => matchesFilter(a, q.filter)) as T[], hasMore, []);
      });
    }
    if (this.kind === 'shift' || this.kind === 'inventory') {
      // {rows, truncated, partial, unparsable, syncStatus} (cloud#85 §4). These routes are newer than the
      // tank filter (#84), so the cloud always filters; the rows are matched again only as a safeguard.
      const query = { ...stockQuery(q.device, q.win, pageIndex, filtered), ...filterParams(q.filter, false) };
      return get<RecordListResponse<T>>(RECORD_PATHS[this.kind], query)
        .then((res) => {
          if (!isRecordListResponse(res)) throw new Error(`unexpected response shape from ${RECORD_PATHS[this.kind as 'shift']}`);
          const sum = syncSummary(res);
          return {
            rows: (res.rows as T[]).filter((r) => matchesFilter(r as { tank: number }, q.filter)),
            hasMore: !!res.truncated,
            notes: sum.notes,
            canSayEmpty: sum.canSayEmpty,
            unsupported: sum.unsupported,
            notOpen: false,
          };
        })
        .catch((err: unknown) => {
          // A cloud before #85 has no such route: gin's bare 404 with no code. Not an error to retry.
          if (err instanceof ApiError && err.status === 404 && !failureCode(err)) {
            return { rows: [] as T[], hasMore: false, notes: [], canSayEmpty: false, unsupported: false, notOpen: true };
          }
          throw err;
        });
    }
    // The same envelope on 200 and 502 (some records unparsable): a 502 still carries the rows that did
    // parse, recovered from ApiError.body. Anything that is not the envelope is a real failure.
    const query = { ...stockQuery(q.device, q.win, pageIndex, filtered), ...filterParams(q.filter, true) };
    return get<OilInOutListResponse>('/api/oil-in-out', query)
      .catch((err: ApiError) => {
        if (isOilInOutListResponse(err.body)) return err.body;
        throw err;
      })
      .then((res) => {
        if (!isOilInOutListResponse(res)) throw new Error('unexpected response shape from /api/oil-in-out');
        const { rows, notes } = summarizeOilInOut(res, q.filter.direction || '', { paged: true });
        return plainPage(rows.filter((r) => matchesFilter(r, q.filter)) as T[], !!res.truncated, notes);
      });
  }

  // Fetches pages of `q` from `pageIndex`; `replace` = the first page of a reload replaces the list.
  private run(seq: number, q: FeedQuery, pageIndex: number, replace: boolean, added = 0, pages = 0): Promise<void> {
    return this.fetchPage(q, pageIndex).then((res) => {
      if (seq !== this.seq) return;
      const first = replace && pages === 0;
      const seen = new Set(first ? [] : this.state.rows.map((r) => this.key(r)));
      // A row can show up twice across a page boundary (a row the cloud could not decode shifts the
      // offsets; records still syncing in can land in a window already shown): drop it.
      const fresh = res.rows.filter((r) => !seen.has(this.key(r)));
      this.query = q;
      this.page = pageIndex + 1;
      this.hasMore = res.hasMore;
      this.lastOk = formatLocalTime(new Date().toISOString(), currentLocale());
      this.runPages = pages + 1;
      const addedNow = added + fresh.length;
      const goOn = shouldAutoContinue(isFiltered(q.filter), addedNow, res.hasMore, pages + 1);
      this.set({
        rows: first ? fresh : this.state.rows.concat(fresh),
        loaded: true,
        error: '',
        stale: '',
        note: res.notes.map((m) => t(m.key, m.params)).join(t('common.listSep')),
        footer: goOn ? 'loading' : res.hasMore ? 'more' : 'end',
        canSayEmpty: res.canSayEmpty,
        unsupported: res.unsupported,
        notOpen: res.notOpen,
      });
      if (goOn) return this.run(seq, q, pageIndex + 1, replace, addedNow, pages + 1);
    });
  }

  // A fresh load from the first page (opened, filter or window changed, pull-down, live alarm). A
  // different query empties the list at once; the same query keeps it until the new page is in.
  reload(q: FeedQuery): Promise<void> {
    if (!this.sameQuery(q)) this.reset();
    const seq = ++this.seq;
    this.busy = true;
    this.runPages = 0;
    this.set({ loading: true });
    return this.run(seq, q, 0, true)
      .catch((err: Error) => {
        if (seq !== this.seq) return;
        this.fail(err, this.runPages > 0);
      })
      .finally(() => {
        if (seq !== this.seq) return;
        this.busy = false;
        this.set({ loading: false });
      });
  }

  // `midRun`: a later page of a run failed after earlier ones arrived — keep them, offer a retry.
  // Otherwise a list already on screen is kept and marked out of date; with nothing loaded, the reason.
  private fail(err: Error, midRun: boolean) {
    this.onFailure?.(err);
    wx.showToast({ title: describeError(err), icon: 'none' });
    if (midRun) this.set({ footer: 'error' });
    else if (this.state.loaded) this.set({ stale: t('detail.stale', { time: this.lastOk }) });
    else this.set({ error: describeError(err) });
  }

  loadMore() {
    const q = this.query;
    if (!q || !this.hasMore || this.busy) return;
    const seq = this.seq;
    this.busy = true;
    this.set({ footer: 'loading' });
    this.run(seq, q, this.page, false)
      .catch((err: Error) => {
        if (seq !== this.seq) return;
        this.onFailure?.(err);
        this.set({ footer: 'error' });
        wx.showToast({ title: describeError(err), icon: 'none' });
      })
      .finally(() => {
        if (seq === this.seq) this.busy = false;
      });
  }

  // Tap on the footer: 'error' asks again; 'more' is how a short list (shorter than the screen, so it
  // never scrolls to the bottom) asks for its next page.
  retryMore() {
    if (this.state.footer === 'error' || this.state.footer === 'more') this.loadMore();
  }

  // The page is gone: anything still in flight is dropped, nothing is emitted.
  dispose() {
    this.seq++;
    this.busy = false;
  }
}
