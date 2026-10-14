import { ApiError, get } from '../../utils/request';
import { FOLLOW_NOTICES, followStationDevice, stationIdFromKey } from '../../utils/stationDevice';
import { AlarmSocket } from '../../utils/ws';
import { roleFromToken } from '../../utils/auth';
import { isReadOnlyRole } from '../../utils/roles';
import { getToken } from '../../utils/storage';
import { describeResolveError, resolveDevice } from '../../utils/scan';
import { classifyEntry, loginUrlForScene, setActiveScene } from '../../utils/sceneEntry';
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
  Device,
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
  rowKey: string;
};
type CommandView = ControlCommand & { methodLabel: string };

function toAlarmView(a: Alarm): AlarmView {
  return {
    ...a,
    typeLabel: label(ALARM_TYPE_LABELS, a.type),
    severityLabel: label(ALARM_SEVERITY_LABELS, a.severity),
    statusLabel: label(ALARM_STATUS_LABELS, a.status),
    timeText: formatLocalTime(a.time),
    // The cloud blanks alarmId for alarms it minted itself, so tank and type are part of the key too, or
    // two such alarms in the same instant would collide (and the append-time dedupe would drop one).
    rowKey: `${a.alarmId}:${a.tank}:${a.type}:${a.status}:${a.time}`,
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
    // The cloud sends eventId "" for cloud-generated rows, so eventId alone is not a unique key.
    rowKey: `${r.eventId}|${r.time}|${r.tank}|${r.direction}`,
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

const INVALID_ENTRY_NOTICE = '入口无效，请重新扫描设备小程序码，或从首页进入设备。';
const RETRY_HINT = '下拉可重试。';

Page({
  data: {
    device: '',
    // false while the station has no device this user may see (replacement not assigned to them, or the
    // device left their scope): the tabs are hidden and `notice` says why. Nothing is queried then.
    deviceActive: true,
    notice: '',
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
  // Identity of "the device this page is showing". Bumped on every switch; every request and the
  // socket callback capture it and drop their result if it moved on, so nothing that was asked for the
  // old device can write into the new device's view. (The alarms/stock lists have their own seqs too,
  // which a switch also bumps.)
  deviceGen: 0,
  snapshotSeq: 0,
  commandsSeq: 0,
  // The station this page was opened from (contract v1.2 §8.1): a number only when opened from a
  // station entry on the home page; the device then follows the station's current device
  // (utils/stationDevice.ts followStationDevice). Opened by device name alone (我的记录) it stays
  // undefined and the page is an independent view of that device: it never switches, and only checks
  // the device is still in this user's scope.
  stationId: undefined as number | null | undefined,
  associationBusy: false,
  // Set while the entry has not yet produced a device (see retryEntry). `scene` is the code to locate.
  entryPending: null as 'scene' | 'station' | null,
  scene: '',
  commandsPollTimer: null as ReturnType<typeof setTimeout> | null,
  // Set from the `direction` query param when arriving from the 我的记录 page's 出库/入库 entries
  // (device-detail itself only has one combined 出入库记录 tab; those two rows are the same tab
  // pre-filtered by direction).
  oilInOutDirection: '' as '' | 'IN' | 'OUT',

  // Paging state of the two history tabs. *Seq is bumped by every fresh load and every range change, so
  // a response that arrives after the list was restarted is dropped. *Window is fixed for the whole run
  // of pages (a rolling preset resolved once), otherwise new records would shift the offsets. *Page is
  // how many pages were consumed, which is what the next offset is computed from. *Busy is held by a fresh
  // load AND by a load-more for their whole run (including a filtered stock list fetching on its own), so
  // two requests can never ask for the same page; a restart clears it, and only the run that still owns the
  // seq may clear it afterwards.
  alarmsSeq: 0,
  alarmsPage: 0,
  alarmsHasMore: false,
  alarmsBusy: false,
  alarmsWindow: null as QueryWindow | null,
  stockSeq: 0,
  stockPage: 0,
  stockHasMore: false,
  stockBusy: false,
  stockWindow: null as QueryWindow | null,

  onLoad(query: Record<string, string>) {
    const device = query.device || '';
    // A view-only account (customer_user) gets no 控制命令 tab: it cannot issue commands and the cloud
    // does not show it the command history either (tank-grants-contract v1.0 §2).
    const tabs = isReadOnlyRole(roleFromToken(getToken())) ? TABS.filter((t) => t.key !== 'commands') : TABS;
    const requestedTab = query.tab as TabKey | undefined;
    const tab: TabKey = tabs.some((t) => t.key === requestedTab) ? (requestedTab as TabKey) : 'snapshot';
    this.oilInOutDirection = query.direction === 'IN' || query.direction === 'OUT' ? query.direction : '';
    this.stationId = stationIdFromKey(query.station);
    this.setData({ device, tabs, activeTab: tab, today: formatDate(new Date()) });
    wx.setNavigationBarTitle({ title: device || '设备详情' });
    // Opened from a device mini-program code: the scene is the device's printed code, not yet a device
    // name. Locate it first; nothing is queried until the cloud has answered.
    const entry = classifyEntry(query);
    if (entry.kind === 'scene') {
      this.scene = entry.scene;
      this.entryPending = 'scene';
      setActiveScene(entry.scene);
      this.enterByScene(entry.scene);
      return;
    }
    if (entry.kind === 'invalid') {
      this.setData({ deviceActive: false, notice: INVALID_ENTRY_NOTICE });
      return;
    }
    if (entry.kind === 'station') {
      // The device name on the query is only what the home list showed a moment ago: the station may
      // have been replaced since. Decide first, then query and subscribe.
      this.entryPending = 'station';
      this.enterByStation();
      return;
    }
    this.loadTabIfNeeded(tab);
    this.openSocket(device);
    // Catch a replacement that happened between the home list being drawn and this page opening.
    this.refreshAssociation();
  },

  // Entry that has not produced a device yet (scene not located, station not decided): run it again.
  // Used by pull-down and by coming back to the page. No-op once the entry resolved.
  retryEntry(): Promise<unknown> {
    // Not signed in: the login redirect from onLoad is already under way; do not start a second one.
    if (this.entryPending === 'scene' && this.scene && getToken()) return this.enterByScene(this.scene);
    if (this.entryPending === 'station') return this.enterByStation();
    return Promise.resolve();
  },

  // Mini-program-code entry. Not signed in → login first (it brings the user back here with the same
  // scene). Signed in → POST /api/devices/resolve, then show that device exactly like any other
  // entry; a failure is shown as a notice here, never as a dead session, and the scene is kept so a
  // pull-down locates it again.
  enterByScene(scene: string): Promise<unknown> {
    if (!getToken()) {
      wx.reLaunch({ url: loginUrlForScene(scene) });
      return Promise.resolve();
    }
    if (this.associationBusy) return Promise.resolve();
    this.associationBusy = true;
    this.setData({ deviceActive: false, notice: '', loading: true });
    const gen = this.deviceGen;
    return resolveDevice(scene)
      .then((deviceName) => {
        if (gen !== this.deviceGen) return;
        this.entryPending = null;
        this.setData({ loading: false });
        this.switchDevice(deviceName, '');
      })
      .catch((err: Error) => {
        if (gen !== this.deviceGen) return;
        this.setData({ loading: false, deviceActive: false, notice: `${describeResolveError(err)} ${RETRY_HINT}` });
      })
      .finally(() => {
        this.associationBusy = false;
      });
  },

  // Station entry: GET /api/devices decides which device the station shows before any request or socket
  // is started. A failed read shows a notice and keeps nothing on screen (the passed device name may be
  // the replaced one); pull-down reads again.
  enterByStation(): Promise<unknown> {
    if (this.associationBusy) return Promise.resolve();
    this.associationBusy = true;
    this.setData({ deviceActive: false, notice: '', loading: true });
    const gen = this.deviceGen;
    const device = this.data.device as string;
    return get<{ rows: Device[] }>('/api/devices')
      .then((res) => {
        if (gen !== this.deviceGen) return;
        this.entryPending = null;
        const decision = followStationDevice(res.rows, device, this.stationId);
        if (decision.kind === 'none') {
          this.switchDevice('', FOLLOW_NOTICES[decision.reason]);
        } else {
          this.switchDevice(decision.kind === 'switch' ? decision.device : device, '');
        }
      })
      .catch((err: Error) => {
        if (gen !== this.deviceGen) return;
        this.setData({ loading: false, deviceActive: false, notice: `${err.message || '获取设备信息失败'} ${RETRY_HINT}` });
      })
      .finally(() => {
        this.associationBusy = false;
      });
  },

  // One socket per device. A socket's own messages are checked against the generation it was opened
  // for, so a push that was already in flight when the device switched is dropped.
  openSocket(device: string) {
    const gen = this.deviceGen;
    const socket = new AlarmSocket(device, (msg: AlarmPushMessage) => {
      if (gen !== this.deviceGen || socket !== this.socket) return;
      this.setData({ liveAlarm: `${label(ALARM_TYPE_LABELS, msg.type)}：${msg.msg}` });
      // A live push means the alarm list is stale if the user is looking at it.
      // Only when the list is the live tail: a rolling range, and the user has not paged down (a reload
      // would throw their place away). Otherwise the banner above is the notification.
      if (this.data.activeTab === 'alarms' && this.data.rangeKey !== 'custom' && this.alarmsPage <= 1) {
        this.loadAlarms();
      }
    });
    this.socket = socket;
    socket.connect();
  },

  // Re-read GET /api/devices and let the station decide which device this page shows (contract v1.0
  // §3.3: no read-by-station endpoint, only station_id on the device rows). Called on open, on show,
  // on pull-down and after a "not authorized for this device" answer.
  refreshAssociation(): Promise<unknown> {
    if (this.entryPending) return this.retryEntry();
    if (this.associationBusy) return Promise.resolve();
    // Nothing shown and no station to follow (a scene still being located, or its locate failed): the
    // notice on screen is the answer, and 'device-gone' must not overwrite it.
    if (!this.data.device && typeof this.stationId !== 'number') return Promise.resolve();
    this.associationBusy = true;
    const gen = this.deviceGen;
    return get<{ rows: Device[] }>('/api/devices')
      .then((res) => {
        if (gen !== this.deviceGen) return;
        const devices = res.rows;
        const device = this.data.device as string;
        const decision = followStationDevice(devices, device, this.stationId);
        if (decision.kind === 'switch') {
          this.switchDevice(decision.device, '');
        } else if (decision.kind === 'none') {
          const notice = FOLLOW_NOTICES[decision.reason];
          // Already showing this notice with nothing held: nothing to clear again.
          if (this.data.device || this.data.notice !== notice) this.switchDevice('', notice);
        }
      })
      // A failed re-read changes nothing: the current device stays as it was.
      .catch(() => undefined)
      .finally(() => {
        this.associationBusy = false;
      });
  },

  // Everything held for the old device is dropped here — latest value, tanks, alarms, stock records,
  // commands, live banner, paging state, the socket and the poll timer — and requests still in flight
  // for it are invalidated, before anything is asked for the new one. `device` '' = show the notice and
  // query nothing. Nothing is migrated: the new device's view starts empty and says so.
  switchDevice(device: string, notice: string) {
    this.deviceGen++;
    this.snapshotSeq++;
    this.commandsSeq++;
    this.alarmsSeq++;
    this.stockSeq++;
    this.alarmsPage = 0;
    this.stockPage = 0;
    this.alarmsHasMore = false;
    this.stockHasMore = false;
    this.alarmsBusy = false;
    this.stockBusy = false;
    this.alarmsWindow = null;
    this.stockWindow = null;
    this.stopCommandsPoll();
    this.socket?.close();
    this.socket = null;
    this.setData({
      device,
      deviceActive: !!device,
      notice,
      loading: false,
      liveAlarm: '',
      snapshotTs: '',
      tanks: [],
      snapshotLoaded: false,
      alarms: [],
      alarmsLoaded: false,
      alarmsFooter: '',
      oilInOut: [],
      oilInOutNote: '',
      oilInOutLoaded: false,
      oilInOutFooter: '',
      commands: [],
      commandsLoaded: false,
      rangeHint: '',
    });
    wx.setNavigationBarTitle({ title: device || '设备详情' });
    if (!device) return;
    this.loadTabIfNeeded(this.data.activeTab);
    this.openSocket(device);
  },

  onShow() {
    // Back from another page (or the app was in the background): the back office may have replaced the
    // station's device meanwhile.
    this.refreshAssociation();
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
    setActiveScene(null);
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
    if (!this.data.deviceActive) {
      this.refreshAssociation().finally(() => wx.stopPullDownRefresh());
      return;
    }
    this.refreshAssociation();
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
    if (!this.data.device) return Promise.resolve();
    const device = this.data.device as string;
    const seq = ++this.snapshotSeq;
    this.setData({ loading: true });
    return get<LatestTelemetryResponse>(`/api/devices/${encodeURIComponent(device)}/telemetry/latest`)
      .then((res) => {
        if (seq !== this.snapshotSeq) return;
        this.setData({ snapshotTs: res.ts, tanks: toTankViews(res, device), snapshotLoaded: true });
      })
      .catch((err: ApiError) => {
        if (seq !== this.snapshotSeq) return;
        if (isNeverReported(err)) {
          // Authorized and provisioned, but no data — a real state, not an error. Also what a replaced
          // device's successor looks like until it reports (and yeweiyi-cloud#28 can answer 404 for a
          // device that stopped reporting): an empty state, never the previous device's readings.
          this.setData({ tanks: [], snapshotTs: '', snapshotLoaded: true });
        } else {
          // 403 here is "not authorized for this device": no assignment for this device. Say what to do
          // instead of the cloud's English text; nothing in the app can grant it.
          wx.showToast({
            title: err.status === 403 ? '你暂无该设备的查看权限，请联系客户管理员分配' : err.message,
            icon: 'none',
            duration: 3000,
          });
          // "not authorized for this device" is an object-level refusal, not a dead session (request.ts
          // keeps the session): the device may have left this user's scope, so re-resolve the station.
          if (err.status === 403) this.refreshAssociation();
        }
      })
      .finally(() => {
        if (seq === this.snapshotSeq) this.setData({ loading: false });
      });
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
    // `today` bounds the date pickers; recompute it, the page may have been open past midnight.
    this.setData({ rangeKey: key, today: formatDate(new Date()) });
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
    this.alarmsBusy = false;
    this.stockBusy = false;
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
    this.alarmsBusy = true;
    this.setData({ loading: true });
    return this.fetchAlarmPage(win, 0)
      .then(({ items, hasMore }) => {
        if (seq !== this.alarmsSeq) return;
        // The window and page counter change only together with the list: if this refresh fails, the old
        // list stays on screen and paging must keep continuing that list, not restart at page 0.
        this.alarmsWindow = win;
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
        if (seq !== this.alarmsSeq) return;
        this.alarmsBusy = false;
        this.setData({ loading: false });
      });
  },

  loadMoreAlarms() {
    if (!this.alarmsHasMore || this.alarmsBusy || !this.alarmsWindow) return;
    const seq = this.alarmsSeq;
    this.alarmsBusy = true;
    this.setData({ alarmsFooter: 'loading' });
    this.fetchAlarmPage(this.alarmsWindow, this.alarmsPage)
      .then(({ items, hasMore }) => {
        if (seq !== this.alarmsSeq) return;
        this.alarmsPage += 1;
        this.alarmsHasMore = hasMore;
        // A row can show up twice across a page boundary (an unreadable row the cloud skipped shifts the
        // offsets by one; records still syncing in can land inside a window already shown), so drop any
        // row whose key is already in the list.
        const seen = new Set(this.data.alarms.map((a: AlarmView) => a.rowKey));
        const fresh = items.map(toAlarmView).filter((a) => !seen.has(a.rowKey));
        this.setData({
          alarms: this.data.alarms.concat(fresh),
          alarmsFooter: hasMore ? 'more' : 'end',
        });
      })
      .catch((err: Error) => {
        if (seq !== this.alarmsSeq) return;
        this.setData({ alarmsFooter: 'error' });
        wx.showToast({ title: err.message, icon: 'none' });
      })
      .finally(() => {
        if (seq === this.alarmsSeq) this.alarmsBusy = false;
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
      const first = reset && pages === 0;
      // Drop rows already shown (see the alarm list: a shifted offset or a record that synced in late).
      const seen = new Set(first ? [] : this.data.oilInOut.map((r: OilInOutView) => r.rowKey));
      const views = rows.map(toOilInOutView).filter((r) => !seen.has(r.rowKey));
      this.stockPage += 1;
      this.stockHasMore = !!res.truncated;
      const addedNow = added + views.length;
      const goOn = shouldAutoContinue(filtered, addedNow, this.stockHasMore, pages + 1);
      this.setData({
        oilInOut: first ? views : this.data.oilInOut.concat(views),
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
    this.stockBusy = true;
    this.setData({ loading: true });
    return this.fetchStock(seq, win, true)
      .catch((err: Error) => {
        if (seq !== this.stockSeq) return;
        wx.showToast({ title: err.message, icon: 'none' });
        if (this.stockPage > 0) {
          // A later page of this run failed (a filtered list fetches several on its own): what was
          // already fetched stays on screen and the footer offers a retry, instead of wiping it.
          this.setData({ oilInOutFooter: 'error' });
        } else {
          this.setData({ oilInOut: [], oilInOutNote: err.message, oilInOutLoaded: true, oilInOutFooter: '' });
        }
      })
      .finally(() => {
        if (seq !== this.stockSeq) return;
        this.stockBusy = false;
        this.setData({ loading: false });
      });
  },

  loadMoreOilInOut() {
    if (!this.stockHasMore || this.stockBusy || !this.stockWindow) return;
    const seq = this.stockSeq;
    this.stockBusy = true;
    this.setData({ oilInOutFooter: 'loading' });
    this.fetchStock(seq, this.stockWindow, false)
      .catch((err: Error) => {
        if (seq !== this.stockSeq) return;
        this.setData({ oilInOutFooter: 'error' });
        wx.showToast({ title: err.message, icon: 'none' });
      })
      .finally(() => {
        if (seq === this.stockSeq) this.stockBusy = false;
      });
  },

  // Scrolled to the bottom of the page: the active history tab asks for its next page.
  onReachBottom() {
    if (this.data.activeTab === 'alarms') this.loadMoreAlarms();
    else if (this.data.activeTab === 'oilInOut') this.loadMoreOilInOut();
  },

  // Tap on the footer (加载失败，点击重试 / 点击加载更多).
  onRetryMore() {
    // 'error': a failed page leaves hasMore as it was (only a success changes it), so retrying is just asking
    // again. 'more': a short list (a filtered 入库/出库 list can end up shorter than the screen) never reaches
    // the bottom by scrolling, so tapping the footer is the way to ask for the next page.
    const footer = this.data.activeTab === 'alarms' ? this.data.alarmsFooter : this.data.oilInOutFooter;
    if (footer !== 'error' && footer !== 'more') return;
    if (this.data.activeTab === 'alarms') this.loadMoreAlarms();
    else if (this.data.activeTab === 'oilInOut') this.loadMoreOilInOut();
  },

  loadCommands() {
    if (!this.data.device) return Promise.resolve();
    const seq = ++this.commandsSeq;
    this.setData({ loading: true });
    return get<ListCommandsResponse>(
      `/api/devices/${encodeURIComponent(this.data.device)}/commands`,
      { limit: 50 },
    )
      .then((res) => {
        if (seq !== this.commandsSeq) return;
        this.setData({ commands: res.commands.map(toCommandView), commandsLoaded: true });
        this.scheduleCommandsPoll(res.commands);
      })
      .catch((err: Error) => {
        if (seq !== this.commandsSeq) return;
        wx.showToast({ title: err.message, icon: 'none' });
      })
      .finally(() => {
        if (seq === this.commandsSeq) this.setData({ loading: false });
      });
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
