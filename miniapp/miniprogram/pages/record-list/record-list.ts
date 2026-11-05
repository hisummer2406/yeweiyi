import { currentLocale, setNavTitle, t, texts } from '../../utils/i18n';
import { get } from '../../utils/request';
import { formatLocalTime } from '../../utils/format';
import { loadRecordsContext } from '../../utils/stationPref';
import { FOLLOW_NOTICES } from '../../utils/stationDevice';
import { describeError } from '../../utils/errors';
import { RecordFeed, failureCode, type FeedKind, type FeedRow, type FeedState, type Footer } from '../../utils/recordFeed';
import {
  COLUMNS,
  alarmCells,
  alarmRowKey,
  directionOf,
  groupOf,
  inventoryCells,
  inventoryRowKey,
  isRecordType,
  mediumText,
  shiftCells,
  shiftRowKey,
  stockCells,
  stockRowKey,
  type CellFormat,
  type RecordType,
} from '../../utils/recordColumns';
import {
  RANGE_PRESETS,
  customRangeProblem,
  formatDate,
  resolveWindow,
  type QueryWindow,
  type RangeKey,
  type RangeProblem,
  type RecordsRange,
} from '../../utils/recordsRange';
import { ALARM_SEVERITY_KEYS, ALARM_STATUS_KEYS, ALARM_TYPE_KEYS, label } from '../../utils/labels';
import type { Alarm, DeviceTanksResponse, OilInOutRow, ShiftRecordRow, StockRecordRow } from '../../types/api';

// One record type of 我的记录 as a table (miniapp#16, #17): the home page's current station and its one
// device, all visible tanks by default with a tank filter, columns in the device display's order.
// No device picker: the device is decided here from the station on every show, never taken from the URL.

const TITLE_KEYS: Record<RecordType, string> = {
  in: 'records.stockIn',
  out: 'records.stockOut',
  alarm: 'records.alarm',
  stock: 'records.inventory',
  shift: 'records.shift',
};

const RANGE_HINT_KEYS: Record<Exclude<RangeProblem, ''>, string> = {
  incomplete: 'detail.rangeIncomplete',
  invalid: 'detail.rangeInvalid',
  order: 'detail.rangeOrder',
  future: 'detail.rangeFuture',
};

// All visible tanks (no tank filter).
const ALL = -1;

const FEED_KINDS: Record<RecordType, FeedKind> = { in: 'stock', out: 'stock', alarm: 'alarm', shift: 'shift', stock: 'inventory' };

// not-open: the cloud has no endpoint for this type (a cloud before #85); no-station / blocked / error: no device to read (see the
// records page); ok: the table.
type State = 'loading' | 'ok' | 'not-open' | 'no-station' | 'blocked' | 'error';

type RowView = {
  key: string;
  tank: string;
  cells: string[];
  // Extra row classes: alternate shading per shift / stock snapshot (the rows of one record are one group).
  cls: string;
  // Alarm rows: ACTIVE / CLEARED keeps its own mark, and a tap shows the full text, status and severity.
  active?: boolean;
  // The alarm's own sheet: every field of the record, the message in full.
  detail?: {
    kind: string;
    active: boolean;
    status: string;
    rows: { label: string; value: string }[];
    text: string;
  };
};

type Row = FeedRow;

function cellClass(align: 'left' | 'right', wide?: boolean): string {
  return `rt-cell ${align === 'right' ? 'rt-num' : ''} ${wide ? 'rt-wide' : ''}`;
}

Page({
  data: {
    text: texts('records'),
    dtext: texts('detail'),
    type: 'alarm' as RecordType,
    title: '',
    state: 'loading' as State,
    notice: '',
    // Informational, does not stop the table (remembered station replaced, a tank no longer visible).
    infoNotice: '',
    stationName: '',

    tankHeader: '',
    header: [] as { title: string; cls: string }[],
    cellCls: [] as string[],
    rows: [] as RowView[],
    loaded: false,
    loading: false,
    error: '',
    stale: '',
    note: '',
    footer: '' as Footer,
    canSayEmpty: true,
    // 交班/库存: the device never reported this kind of record.
    unsupported: false,

    tankOptions: [] as { tank: number; label: string }[],
    tank: ALL,

    rangePresets: RANGE_PRESETS.map((p) => ({ key: p.key, label: t(p.labelKey) })),
    rangeKey: '24h' as RangeKey,
    customStart: '',
    customEnd: '',
    today: formatDate(new Date()),
    rangeHint: '',

    detail: null as RowView['detail'] | null,
  },

  feed: null as RecordFeed<Row> | null,
  // What the table on screen belongs to; a change of either drops everything held for the old one.
  device: '',
  stationKey: '',
  // An old device-detail link that was verified for this station: used instead of the home page's.
  pinned: undefined as string | undefined,
  seq: 0,
  tanksSeq: 0,
  wasHidden: false,

  onLoad(query: Record<string, string>) {
    const type: RecordType = isRecordType(query.type) ? query.type : 'alarm';
    this.pinned = query.station && /^\d+$/.test(query.station) ? query.station : undefined;
    const cols = COLUMNS[type];
    const title = (c: { titleKey: string; unit: string }) => `${t(c.titleKey)}${c.unit ? `(${c.unit})` : ''}`;
    this.setData({
      type,
      title: t(TITLE_KEYS[type]),
      tankHeader: title(cols[0]),
      header: cols.slice(1).map((c) => ({ title: title(c), cls: cellClass(c.align, c.wide) })),
      cellCls: cols.slice(1).map((c) => cellClass(c.align, c.wide)),
      today: formatDate(new Date()),
    });
    this.feed = new RecordFeed<Row>(
      FEED_KINDS[type],
      (s) => this.showRows(s),
      (err) => this.onRefused(err),
    );
  },

  onShow() {
    this.setData({ text: texts('records'), dtext: texts('detail') });
    setNavTitle(TITLE_KEYS[this.data.type as RecordType]);
    // Every show: the home page may have switched station or the station's device may have been replaced.
    const refresh = this.wasHidden;
    this.wasHidden = false;
    this.resolve(refresh);
  },

  onHide() {
    this.wasHidden = true;
  },

  onUnload() {
    this.seq++;
    this.tanksSeq++;
    this.feed?.dispose();
  },

  // Decides the station and device again. Same device as on screen: reload only when asked (`refresh`).
  // Another device (home switched station, or the station's device was replaced): drop the old device's
  // rows, tank filter and paging, then load the new one. No device: say why, query nothing.
  resolve(refresh: boolean): Promise<unknown> {
    const seq = ++this.seq;
    if (!this.device) this.setData({ state: 'loading' });
    return loadRecordsContext(this.pinned)
      .then((ctx) => {
        if (seq !== this.seq) return undefined;
        if (ctx.kind !== 'ok') {
          this.dropDevice();
          if (ctx.kind === 'no-station') this.setData({ state: 'no-station', notice: t('records.noStation'), stationName: '' });
          else {
            const notice = ctx.kind === 'ambiguous' ? t(FOLLOW_NOTICES.ambiguous) : t('records.emptyStation');
            this.setData({ state: 'blocked', notice, stationName: ctx.stationName });
          }
          return undefined;
        }
        this.setData({ stationName: ctx.stationName, notice: '', ...(ctx.lost ? { infoNotice: t('home.lostStation') } : {}) });
        const device = ctx.device.device_name;
        if (device === this.device && ctx.stationKey === this.stationKey) {
          return refresh ? this.reload() : undefined;
        }
        this.dropDevice();
        this.device = device;
        this.stationKey = ctx.stationKey;
        this.setData({ state: 'ok' });
        this.loadTanks();
        return this.reload();
      })
      .catch((err: Error) => {
        if (seq !== this.seq) return;
        // Not knowing the station's device means not showing any device's records (the old one may be
        // the replaced one).
        this.dropDevice();
        this.setData({ state: 'error', notice: describeError(err) });
      });
  },

  dropDevice() {
    this.device = '';
    this.stationKey = '';
    this.tanksSeq++;
    this.feed?.reset();
    this.setData({ tankOptions: [], tank: ALL, infoNotice: '', detail: null });
  },

  // The tank filter offers the tanks this user may see on the device (GET /api/devices/:device/tanks:
  // from the latest snapshot, already filtered to a customer_user's grants). A device that never reported
  // has none: only "all". A failure leaves "all" too — the list itself still works.
  loadTanks() {
    const seq = ++this.tanksSeq;
    const device = this.device;
    get<DeviceTanksResponse>(`/api/devices/${encodeURIComponent(device)}/tanks`)
      .then((res) => {
        if (seq !== this.tanksSeq) return;
        const tanks = Array.from(new Set((res.tanks || []).map((x) => x.index))).sort((a, b) => a - b);
        this.setData({ tankOptions: tanks.map((n) => ({ tank: n, label: t('tank.no', { n }) })) });
      })
      .catch(() => undefined);
  },

  onPickTank(e: WechatMiniprogram.BaseEvent) {
    const tank = Number(e.currentTarget.dataset.tank);
    if (tank === this.data.tank) return;
    this.setData({ tank, infoNotice: '' });
    this.feed?.reset();
    this.reload();
  },

  // 403 tank_not_authorized: that tank is not this user's any more. Not "no records" and not a dead
  // session: drop it from the filter and go back to all tanks.
  onRefused(err: Error) {
    if (failureCode(err) !== 'tank_not_authorized') return;
    const tank = this.data.tank as number;
    const options = (this.data.tankOptions as { tank: number }[]).filter((o) => o.tank !== tank);
    this.setData({
      tankOptions: options as { tank: number; label: string }[],
      tank: ALL,
      infoNotice: tank === ALL ? '' : t('records.tankRevoked', { n: tank }),
    });
    this.feed?.reset();
    this.reload();
  },

  // ---- Time range ----

  currentRange(): RecordsRange {
    return { key: this.data.rangeKey, startDate: this.data.customStart, endDate: this.data.customEnd };
  },

  windowForReload(): QueryWindow | null {
    const range = this.currentRange();
    const now = new Date();
    const problem = customRangeProblem(range, now);
    this.setData({ rangeHint: problem ? t(RANGE_HINT_KEYS[problem]) : '' });
    return problem ? null : resolveWindow(range, now);
  },

  onPickRange(e: WechatMiniprogram.BaseEvent) {
    const key = e.currentTarget.dataset.key as RangeKey;
    if (key === this.data.rangeKey) return;
    this.setData({ rangeKey: key, today: formatDate(new Date()) });
    this.feed?.reset();
    this.reload();
  },

  onStartDate(e: WechatMiniprogram.CustomEvent) {
    this.setData({ customStart: e.detail.value as string });
    this.feed?.reset();
    this.reload();
  },

  onEndDate(e: WechatMiniprogram.CustomEvent) {
    this.setData({ customEnd: e.detail.value as string });
    this.feed?.reset();
    this.reload();
  },

  // ---- List ----

  reload(): Promise<unknown> {
    const feed = this.feed;
    const win = this.windowForReload();
    if (!feed || !this.device || !win) return Promise.resolve();
    const tank = this.data.tank as number;
    const direction = directionOf(this.data.type as RecordType);
    return feed.reload({
      device: this.device,
      win,
      filter: { ...(tank === ALL ? {} : { tank }), ...(direction ? { direction } : {}) },
    });
  },

  showRows(s: FeedState<Row>) {
    // A cloud without this record type's endpoint: no table, no "no records" (a later reload may find it).
    if (s.notOpen) {
      this.setData({ state: 'not-open', notice: t('records.notOpen'), rows: [], loaded: false, loading: s.loading, footer: '' });
      return;
    }
    if (this.data.state === 'not-open') this.setData({ state: 'ok', notice: '' });
    const type = this.data.type as RecordType;
    const f: CellFormat = { time: (iso: string) => formatLocalTime(iso, currentLocale()), tr: (key: string) => t(key) };
    let group = '';
    let shade = true;
    const rows: RowView[] = s.rows.map((r) => {
      if (type === 'alarm') {
        const a = r as Alarm;
        const status = label(ALARM_STATUS_KEYS, a.status);
        const kind = label(ALARM_TYPE_KEYS, a.type);
        const info = `[${status}] ${kind}${a.text ? `: ${a.text}` : ''}`;
        return {
          key: alarmRowKey(a),
          tank: String(a.tank),
          cls: '',
          cells: alarmCells(a, info, f),
          active: a.status === 'ACTIVE',
          detail: {
            kind,
            active: a.status === 'ACTIVE',
            status,
            rows: [
              { label: t('table.tank'), value: t('tank.no', { n: a.tank }) },
              { label: t('table.medium'), value: mediumText(a.oilTypeIndex, a.oilType, f.tr) },
              { label: t('records.severity'), value: label(ALARM_SEVERITY_KEYS, a.severity) },
              { label: t('table.alarmTime'), value: f.time(a.time) },
              ...(typeof a.value === 'number' && Number.isFinite(a.value) ? [{ label: t('records.value'), value: String(a.value) }] : []),
            ],
            text: a.text,
          },
        };
      }
      if (type === 'shift' || type === 'stock') {
        // Rows of one shift / snapshot are adjacent (the cloud orders by time, then record, then tank).
        const g = groupOf(type, r as { shiftId?: string; recordId?: string });
        if (g !== group) {
          group = g;
          shade = !shade;
        }
        const cls = shade ? 'rt-shade' : '';
        if (type === 'shift') {
          const x = r as ShiftRecordRow;
          return { key: shiftRowKey(x), tank: String(x.tank), cls, cells: shiftCells(x, f) };
        }
        const x = r as StockRecordRow;
        return { key: inventoryRowKey(x), tank: String(x.tank), cls, cells: inventoryCells(x, f) };
      }
      const o = r as OilInOutRow;
      return { key: stockRowKey(o), tank: String(o.tank), cls: '', cells: stockCells(o, f) };
    });
    this.setData({
      rows,
      loaded: s.loaded,
      loading: s.loading,
      error: s.error,
      stale: s.stale,
      note: s.note,
      footer: s.footer,
      canSayEmpty: s.canSayEmpty,
      unsupported: s.unsupported,
    });
  },

  onTapRow(e: WechatMiniprogram.BaseEvent) {
    const row = (this.data.rows as RowView[])[Number(e.currentTarget.dataset.index)];
    if (row && row.detail) this.setData({ detail: row.detail });
  },

  onCloseDetail() {
    this.setData({ detail: null });
  },

  onReachBottom() {
    this.feed?.loadMore();
  },

  onRetryMore() {
    this.feed?.retryMore();
  },

  onRetry() {
    if (this.data.state === 'ok') this.reload();
    else this.resolve(true);
  },

  onGoHome() {
    wx.switchTab({ url: '/pages/devices/devices' });
  },

  onPullDownRefresh() {
    this.resolve(true).finally(() => wx.stopPullDownRefresh());
  },
});

export {};
