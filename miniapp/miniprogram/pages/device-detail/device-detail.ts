import { ApiError, get } from '../../utils/request';
import { AlarmSocket } from '../../utils/ws';
import { formatLocalTime, roundFields } from '../../utils/format';
import { isOilInOutListResponse, summarizeOilInOut } from '../../utils/oilInOut';
import {
  RANGE_PRESETS,
  alarmQuery,
  customRangeProblem,
  formatDate,
  resolveWindow,
  shouldAutoContinue,
  splitAlarmPage,
  stockQuery,
  type QueryWindow,
  type RangeKey,
  type RangeProblem,
  type RecordsRange,
} from '../../utils/recordsRange';
import { isNeverReported, toTankViews, type TankView } from '../../utils/telemetry';
import {
  ALARM_SEVERITY_LABELS,
  ALARM_STATUS_LABELS,
  ALARM_TYPE_LABELS,
  STOCK_DIRECTION_LABELS,
  controlMethodLabel,
  label,
} from '../../utils/labels';
import type {
  Alarm,
  AlarmPushMessage,
  ControlCommand,
  LatestTelemetryResponse,
  ListCommandsResponse,
  OilInOutListResponse,
  OilInOutRow,
} from '../../types/api';

type TabKey = 'snapshot' | 'alarms' | 'oilInOut' | 'commands';

const TABS: { key: TabKey; label: string }[] = [
  { key: 'snapshot', label: '实时数据' },
  { key: 'alarms', label: '报警记录' },
  { key: 'oilInOut', label: '出入库记录' },
  { key: 'commands', label: '控制命令' },
];

// View-model types add pre-computed Chinese labels so WXML never has to embed a lookup — wx:for
// item interpolation can't call a function, so the label has to already be a plain field on the
// row by the time it reaches data.
// The cloud sends RFC3339 UTC strings; the *Text fields are the phone's local wall clock. rowKey is
// for wx:key: one alarmId appears twice (ACTIVE and CLEARED), so alarmId alone is not a key.
type AlarmView = Alarm & {
  typeLabel: string;
  severityLabel: string;
  statusLabel: string;
  timeText: string;
  rowKey: string;
};
type OilInOutView = OilInOutRow & {
  directionLabel: string;
  timeText: string;
  beginTimeText: string;
  endTimeText: string;
};
type CommandView = ControlCommand & { methodLabel: string };

function toAlarmView(a: Alarm): AlarmView {
  return {
    ...a,
    typeLabel: label(ALARM_TYPE_LABELS, a.type),
    severityLabel: label(ALARM_SEVERITY_LABELS, a.severity),
    statusLabel: label(ALARM_STATUS_LABELS, a.status),
    timeText: formatLocalTime(a.time),
    rowKey: `${a.alarmId}:${a.status}:${a.time}`,
  };
}

const OIL_IN_OUT_NUMBER_FIELDS = [
  'beginLevelMm',
  'endLevelMm',
  'beginVolumeL',
  'endVolumeL',
  'beginTempC',
  'endTempC',
  'beginWeightKg',
  'endWeightKg',
  'netVolumeL',
  'duringDeliveryVolumeL',
  'deliveredVolumeL',
] as const;

function toOilInOutView(r: OilInOutRow): OilInOutView {
  return {
    ...roundFields(r, OIL_IN_OUT_NUMBER_FIELDS),
    directionLabel: label(STOCK_DIRECTION_LABELS, r.direction),
    timeText: formatLocalTime(r.time),
    beginTimeText: formatLocalTime(r.beginTime),
    endTimeText: formatLocalTime(r.endTime),
  };
}

function toCommandView(c: ControlCommand): CommandView {
  return { ...c, methodLabel: controlMethodLabel(c.method) };
}

// Bottom-of-list state of a paged tab: more = idle with more to load, loading, end = nothing further,
// error = the last page failed (tap to retry). '' = no footer.
type Footer = '' | 'more' | 'loading' | 'end' | 'error';

const RANGE_HINTS: Record<Exclude<RangeProblem, ''>, string> = {
  incomplete: '请选择开始和结束日期',
  invalid: '日期格式不正确',
  order: '开始日期不能晚于结束日期',
  future: '开始日期不能晚于今天',
};

Page({
  data: {
    device: '',
    tabs: TABS,
    activeTab: 'snapshot' as TabKey,
    loading: false,
    liveAlarm: '',

    snapshotTs: '',
    tanks: [] as TankView[],
    snapshotLoaded: false,

    // Time range shared by the 报警记录 and 出入库记录 tabs. Presets roll back from "now" at load time;
    // custom is whole local days. rangeHint says why nothing is shown yet (e.g. dates not picked).
    rangePresets: RANGE_PRESETS.map((p) => ({ key: p.key, label: p.label })),
    rangeKey: '24h' as RangeKey,
    customStart: '',
    customEnd: '',
    today: formatDate(new Date()),
    rangeHint: '',

    alarms: [] as AlarmView[],
    alarmsLoaded: false,
    alarmsFooter: '' as Footer,

    oilInOut: [] as OilInOutView[],
    // Set whenever the endpoint reports it could not show everything — unparsable (current device
    // fault) or legacySkipped (closed historical hole) — so the list is never silently incomplete.
    // See yeweiyi-cloud docs/protocol-v1-stock-event.md.
    oilInOutNote: '',
    oilInOutLoaded: false,
    oilInOutFooter: '' as Footer,

    commands: [] as CommandView[],
    commandsLoaded: false,
  },

  socket: null as AlarmSocket | null,
  commandsPollTimer: null as ReturnType<typeof setTimeout> | null,
  // Set from the `direction` query param when arriving from the 我的记录 page's 出库/入库 entries
  // (device-detail itself only has one combined 出入库记录 tab; those two rows are the same tab
  // pre-filtered by direction).
  oilInOutDirection: '' as '' | 'IN' | 'OUT',

  // Paging state of the two history tabs. *Seq is bumped by every fresh load and every range change, so
  // a response that arrives after the list was restarted is dropped. *Window is fixed for the whole run
  // of pages (a rolling preset resolved once), otherwise new records would shift the offsets. *Page is
  // how many pages were consumed, which is what the next offset is computed from.
  alarmsSeq: 0,
  alarmsPage: 0,
  alarmsHasMore: false,
  alarmsMoreBusy: false,
  alarmsWindow: null as QueryWindow | null,
  stockSeq: 0,
  stockPage: 0,
  stockHasMore: false,
  stockMoreBusy: false,
  stockWindow: null as QueryWindow | null,

  onLoad(query: Record<string, string>) {
    const device = query.device || '';
    const requestedTab = query.tab as TabKey | undefined;
    const tab: TabKey = TABS.some((t) => t.key === requestedTab) ? (requestedTab as TabKey) : 'snapshot';
    this.oilInOutDirection = query.direction === 'IN' || query.direction === 'OUT' ? query.direction : '';
    this.setData({ device, activeTab: tab });
    wx.setNavigationBarTitle({ title: device || '设备详情' });
    this.loadTabIfNeeded(tab);

    this.socket = new AlarmSocket(device, (msg: AlarmPushMessage) => {
      this.setData({ liveAlarm: `${label(ALARM_TYPE_LABELS, msg.type)}：${msg.msg}` });
      // A live push means the alarm list is stale if the user is looking at it.
      // Only when the list is the live tail: a rolling range, and the user has not paged down (a reload
      // would throw their place away). Otherwise the banner above is the notification.
      if (this.data.activeTab === 'alarms' && this.data.rangeKey !== 'custom' && this.alarmsPage <= 1) {
        this.loadAlarms();
      }
    });
    this.socket.connect();
  },

  onShow() {
    // Coming back from issue-command after a submit — refresh so the new command's initial state
    // (usually 'pending' or 'sent') is visible without a manual pull-to-refresh.
    if (this.data.activeTab === 'commands' && this.data.commandsLoaded) {
      this.loadCommands();
    }
  },

  onHide() {
    this.stopCommandsPoll();
  },

  onUnload() {
    this.stopCommandsPoll();
    this.socket?.close();
    this.socket = null;
  },

  onSwitchTab(e: WechatMiniprogram.BaseEvent) {
    const tab = e.currentTarget.dataset.tab as TabKey;
    this.stopCommandsPoll();
    this.setData({ activeTab: tab });
    this.loadTabIfNeeded(tab);
  },

  loadTabIfNeeded(tab: TabKey) {
    if (tab === 'snapshot' && !this.data.snapshotLoaded) this.loadSnapshot();
    if (tab === 'alarms' && !this.data.alarmsLoaded) this.loadAlarms();
    if (tab === 'oilInOut' && !this.data.oilInOutLoaded) this.loadOilInOut();
    if (tab === 'commands' && !this.data.commandsLoaded) this.loadCommands();
  },

  onPullDownRefresh() {
    const reload: Record<TabKey, () => Promise<unknown>> = {
      snapshot: () => this.loadSnapshot(),
      alarms: () => this.loadAlarms(),
      oilInOut: () => this.loadOilInOut(),
      commands: () => this.loadCommands(),
    };
    reload[this.data.activeTab as TabKey]().finally(() => wx.stopPullDownRefresh());
  },

  // GET /api/devices/:device/telemetry/latest — replaces the deleted /api/oilcan/latest entirely.
  loadSnapshot() {
    this.setData({ loading: true });
    return get<LatestTelemetryResponse>(
      `/api/devices/${encodeURIComponent(this.data.device)}/telemetry/latest`,
    )
      .then((res) => {
        this.setData({ snapshotTs: res.ts, tanks: toTankViews(res), snapshotLoaded: true });
      })
      .catch((err: ApiError) => {
        if (isNeverReported(err)) {
          // Authorized and provisioned, but has never reported — a real state, not an error.
          this.setData({ tanks: [], snapshotTs: '', snapshotLoaded: true });
        } else {
          wx.showToast({ title: err.message, icon: 'none' });
        }
      })
      .finally(() => this.setData({ loading: false }));
  },

  // ---- Time range (shared by 报警记录 and 出入库记录) ----

  currentRange(): RecordsRange {
    return { key: this.data.rangeKey, startDate: this.data.customStart, endDate: this.data.customEnd };
  },

  // The window to query for a fresh load, or null when the range cannot be queried yet (custom dates not
  // picked or inconsistent) — then rangeHint says why and the lists stay empty.
  windowForReload(): QueryWindow | null {
    const range = this.currentRange();
    const now = new Date();
    const problem = customRangeProblem(range, now);
    this.setData({ rangeHint: problem ? RANGE_HINTS[problem] : '' });
    return problem ? null : resolveWindow(range, now);
  },

  onPickRange(e: WechatMiniprogram.BaseEvent) {
    const key = e.currentTarget.dataset.key as RangeKey;
    if (key === this.data.rangeKey) return;
    this.setData({ rangeKey: key });
    this.restartHistory();
  },

  onStartDate(e: WechatMiniprogram.CustomEvent) {
    this.setData({ customStart: e.detail.value as string });
    this.restartHistory();
  },

  onEndDate(e: WechatMiniprogram.CustomEvent) {
    this.setData({ customEnd: e.detail.value as string });
    this.restartHistory();
  },

  // A different window: both lists start over. Bumping the seqs drops responses still in flight for the
  // old window; only the tab being looked at loads now, the other one when it is opened.
  restartHistory() {
    this.alarmsSeq++;
    this.stockSeq++;
    this.alarmsPage = 0;
    this.stockPage = 0;
    this.alarmsHasMore = false;
    this.stockHasMore = false;
    this.alarmsMoreBusy = false;
    this.stockMoreBusy = false;
    this.setData({
      loading: false,
      alarms: [],
      alarmsLoaded: false,
      alarmsFooter: '',
      oilInOut: [],
      oilInOutNote: '',
      oilInOutLoaded: false,
      oilInOutFooter: '',
    });
    this.loadTabIfNeeded(this.data.activeTab);
  },

  // ---- 报警记录 ----

  // GET /api/alarms answers a bare array, not {rows: [...]} — see types/api.d.ts's note on Alarm. It has
  // no "more" flag, so a page asks for one extra row (alarmQuery) and splitAlarmPage uses it as the probe.
  fetchAlarmPage(win: QueryWindow, pageIndex: number) {
    return get<Alarm[]>('/api/alarms', alarmQuery(this.data.device, win, pageIndex)).then((rows) => {
      if (!Array.isArray(rows)) throw new Error('报警记录的响应格式不符合预期，请联系管理员');
      return splitAlarmPage(rows);
    });
  },

  // A fresh load of the first page (tab opened, range changed, pull-down refresh, live alarm).
  loadAlarms(): Promise<unknown> {
    const win = this.windowForReload();
    if (!win) return Promise.resolve();
    const seq = ++this.alarmsSeq;
    this.alarmsWindow = win;
    this.alarmsPage = 0;
    this.alarmsMoreBusy = false;
    this.setData({ loading: true });
    return this.fetchAlarmPage(win, 0)
      .then(({ items, hasMore }) => {
        if (seq !== this.alarmsSeq) return;
        this.alarmsPage = 1;
        this.alarmsHasMore = hasMore;
        this.setData({
          alarms: items.map(toAlarmView),
          alarmsLoaded: true,
          alarmsFooter: hasMore ? 'more' : 'end',
        });
      })
      .catch((err: Error) => {
        if (seq !== this.alarmsSeq) return;
        wx.showToast({ title: err.message, icon: 'none' });
      })
      .finally(() => {
        if (seq === this.alarmsSeq) this.setData({ loading: false });
      });
  },

  loadMoreAlarms() {
    if (!this.alarmsHasMore || this.alarmsMoreBusy || !this.alarmsWindow) return;
    const seq = this.alarmsSeq;
    this.alarmsMoreBusy = true;
    this.setData({ alarmsFooter: 'loading' });
    this.fetchAlarmPage(this.alarmsWindow, this.alarmsPage)
      .then(({ items, hasMore }) => {
        if (seq !== this.alarmsSeq) return;
        this.alarmsPage += 1;
        this.alarmsHasMore = hasMore;
        this.setData({
          alarms: this.data.alarms.concat(items.map(toAlarmView)),
          alarmsFooter: hasMore ? 'more' : 'end',
        });
      })
      .catch((err: Error) => {
        if (seq !== this.alarmsSeq) return;
        this.setData({ alarmsFooter: 'error' });
        wx.showToast({ title: err.message, icon: 'none' });
      })
      .finally(() => {
        if (seq === this.alarmsSeq) this.alarmsMoreBusy = false;
      });
  },

  // ---- 出入库记录 ----

  // GET /api/oil-in-out answers {rows, parsed, unparsable, legacySkipped, ...} on BOTH its 200 and its
  // 502 path (internal/api/stock.go, stockListResponse) — unlike /api/alarms, which is a bare array.
  // A 502 is the endpoint's own degraded-but-informative response, not a transport failure: request.ts
  // hands the full parsed body back on ApiError.body precisely so it does not collapse to a bare error
  // toast and lose the rows that DID parse. Anything that is not that envelope is a real failure.
  requestStockPage(win: QueryWindow, pageIndex: number): Promise<OilInOutListResponse> {
    const filtered = !!this.oilInOutDirection;
    return get<OilInOutListResponse>('/api/oil-in-out', stockQuery(this.data.device, win, pageIndex, filtered))
      .catch((err: ApiError) => {
        if (isOilInOutListResponse(err.body)) return err.body;
        throw err;
      })
      .then((res) => {
        if (!isOilInOutListResponse(res)) {
          // The response is not the shape this page was written against. Say so rather than throw a TypeError.
          throw new Error('出入库记录的响应格式不符合预期，请联系管理员');
        }
        return res;
      });
  },

  // Fetches stock pages into the list. The 入库/出库 entries filter by direction here in the app (the
  // cloud has no such filter), so one page may add few or no rows: keep going on our own — bounded by
  // shouldAutoContinue — until enough were added or the cloud has no more.
  fetchStock(seq: number, win: QueryWindow, reset: boolean, added = 0, pages = 0): Promise<void> {
    const filtered = !!this.oilInOutDirection;
    return this.requestStockPage(win, this.stockPage).then((res) => {
      if (seq !== this.stockSeq) return;
      const { rows, note } = summarizeOilInOut(res, this.oilInOutDirection, { paged: true });
      const views = rows.map(toOilInOutView);
      this.stockPage += 1;
      this.stockHasMore = !!res.truncated;
      const addedNow = added + views.length;
      const goOn = shouldAutoContinue(filtered, addedNow, this.stockHasMore, pages + 1);
      this.setData({
        oilInOut: reset && pages === 0 ? views : this.data.oilInOut.concat(views),
        oilInOutNote: note,
        oilInOutLoaded: true,
        oilInOutFooter: goOn ? 'loading' : this.stockHasMore ? 'more' : 'end',
      });
      if (goOn) return this.fetchStock(seq, win, reset, addedNow, pages + 1);
    });
  },

  loadOilInOut(): Promise<unknown> {
    const win = this.windowForReload();
    if (!win) return Promise.resolve();
    const seq = ++this.stockSeq;
    this.stockWindow = win;
    this.stockPage = 0;
    this.stockMoreBusy = false;
    this.setData({ loading: true });
    return this.fetchStock(seq, win, true)
      .catch((err: Error) => {
        if (seq !== this.stockSeq) return;
        wx.showToast({ title: err.message, icon: 'none' });
        this.setData({ oilInOut: [], oilInOutNote: err.message, oilInOutLoaded: true, oilInOutFooter: '' });
      })
      .finally(() => {
        if (seq === this.stockSeq) this.setData({ loading: false });
      });
  },

  loadMoreOilInOut() {
    if (!this.stockHasMore || this.stockMoreBusy || !this.stockWindow) return;
    const seq = this.stockSeq;
    this.stockMoreBusy = true;
    this.setData({ oilInOutFooter: 'loading' });
    this.fetchStock(seq, this.stockWindow, false)
      .catch((err: Error) => {
        if (seq !== this.stockSeq) return;
        this.setData({ oilInOutFooter: 'error' });
        wx.showToast({ title: err.message, icon: 'none' });
      })
      .finally(() => {
        if (seq === this.stockSeq) this.stockMoreBusy = false;
      });
  },

  // Scrolled to the bottom of the page: the active history tab asks for its next page.
  onReachBottom() {
    if (this.data.activeTab === 'alarms') this.loadMoreAlarms();
    else if (this.data.activeTab === 'oilInOut') this.loadMoreOilInOut();
  },

  // Tap on a footer that says 加载失败，点击重试.
  onRetryMore() {
    // A failed page leaves hasMore as it was (only a success changes it), so retrying is just asking again.
    if (this.data.activeTab === 'alarms' && this.data.alarmsFooter === 'error') this.loadMoreAlarms();
    else if (this.data.activeTab === 'oilInOut' && this.data.oilInOutFooter === 'error') this.loadMoreOilInOut();
  },

  loadCommands() {
    this.setData({ loading: true });
    return get<ListCommandsResponse>(
      `/api/devices/${encodeURIComponent(this.data.device)}/commands`,
      { limit: 50 },
    )
      .then((res) => {
        this.setData({ commands: res.commands.map(toCommandView), commandsLoaded: true });
        this.scheduleCommandsPoll(res.commands);
      })
      .catch((err: Error) => wx.showToast({ title: err.message, icon: 'none' }))
      .finally(() => this.setData({ loading: false }));
  },

  // An ACK is not success: a command stays non-terminal until the device reports its real outcome,
  // so keep re-reading while the user is looking at the list. Branches on the server's `terminal`
  // flag, never on a status list. Stops on tab switch / hide / unload, and on the first failed
  // reload (the .catch in loadCommands does not reschedule), so a dead network is not polled forever.
  scheduleCommandsPoll(commands: ControlCommand[]) {
    this.stopCommandsPoll();
    if (this.data.activeTab !== 'commands' || commands.every((c) => c.terminal)) {
      return;
    }
    this.commandsPollTimer = setTimeout(() => {
      this.commandsPollTimer = null;
      this.loadCommands();
    }, 3000);
  },

  stopCommandsPoll() {
    if (this.commandsPollTimer) {
      clearTimeout(this.commandsPollTimer);
      this.commandsPollTimer = null;
    }
  },

  onIssueCommand() {
    wx.navigateTo({
      url: `/pages/issue-command/issue-command?device=${encodeURIComponent(this.data.device)}`,
    });
  },
});
