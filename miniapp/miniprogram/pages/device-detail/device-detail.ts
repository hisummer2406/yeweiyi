import { describeError } from '../../utils/errors';
import { currentLocale, t as tr, texts } from '../../utils/i18n';
import { onNetworkBack } from '../../utils/network';
import { ApiError, get } from '../../utils/request';
import { FOLLOW_NOTICES, followStationDevice, stationIdFromKey } from '../../utils/stationDevice';
import { AlarmSocket, type SocketState } from '../../utils/ws';
import { roleFromToken } from '../../utils/auth';
import { isReadOnlyRole } from '../../utils/roles';
import { getToken } from '../../utils/storage';
import { describeResolveError, resolveDevice } from '../../utils/scan';
import { classifyEntry, loginUrlForScene, setActiveScene } from '../../utils/sceneEntry';
import { formatLocalTime } from '../../utils/format';
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
import { RecordFeed, failureCode, type FeedState, type Footer } from '../../utils/recordFeed';
import { alarmRowKey } from '../../utils/recordColumns';
import { parseTankParam, pickTank, visibleTanks } from '../../utils/tankPick';
import { isNeverReported, toTankViews, type TankView } from '../../utils/telemetry';
import {
  ALARM_SEVERITY_KEYS,
  ALARM_STATUS_KEYS,
  ALARM_TYPE_KEYS,
  COMMAND_STATUS_KEYS,
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
} from '../../types/api';

// One tank at a time (miniapp#15): the home page opens the tank that was tapped, and the 实时数据 /
// 报警记录 tabs and the live-alarm banner all follow the tank selected here. Stock in/out records are not
// on this page any more: they are under 我的记录 (pages/record-list), bound to the home page's station.
type TabKey = 'snapshot' | 'alarms' | 'commands';

const TABS: { key: TabKey; labelKey: string }[] = [
  { key: 'snapshot', labelKey: 'detail.tabSnapshot' },
  { key: 'alarms', labelKey: 'detail.tabAlarms' },
  // 控制命令 tab 入口已于 2026-10-17 关闭（页面、轮询和 issue-command 都保留，没有别的入口能进）。
  // 想起来再开时把下面这项加回 TABS：
  // { key: 'commands', labelKey: 'detail.tabCommands' },
];

// What WXML renders: the tab list in the current language.
function tabsView(tabs: { key: TabKey; labelKey: string }[]): { key: TabKey; label: string }[] {
  return tabs.map((tab) => ({ key: tab.key, label: tr(tab.labelKey) }));
}

// View-model types add pre-computed labels (in the current language) so WXML never has to embed a lookup —
// wx:for item interpolation can't call a function, so the label has to already be a plain field on the
// row by the time it reaches data.
// The cloud sends RFC3339 UTC strings; the *Text fields are the phone's local wall clock.
type AlarmView = Alarm & {
  tankLabel: string;
  typeLabel: string;
  severityLabel: string;
  statusLabel: string;
  timeText: string;
  rowKey: string;
};
type CommandView = ControlCommand & { methodLabel: string; statusLabel: string };

function toAlarmView(a: Alarm): AlarmView {
  return {
    ...a,
    tankLabel: tr('tank.no', { n: a.tank }),
    typeLabel: label(ALARM_TYPE_KEYS, a.type),
    severityLabel: label(ALARM_SEVERITY_KEYS, a.severity),
    statusLabel: label(ALARM_STATUS_KEYS, a.status),
    timeText: formatLocalTime(a.time, currentLocale()),
    rowKey: alarmRowKey(a),
  };
}

function toCommandView(c: ControlCommand): CommandView {
  return { ...c, methodLabel: controlMethodLabel(c.method), statusLabel: label(COMMAND_STATUS_KEYS, c.status) };
}

const RANGE_HINT_KEYS: Record<Exclude<RangeProblem, ''>, string> = {
  incomplete: 'detail.rangeIncomplete',
  invalid: 'detail.rangeInvalid',
  order: 'detail.rangeOrder',
  future: 'detail.rangeFuture',
};

// No tank selected: the snapshot has not been read yet, or the device has no tank to show (never
// reported, or none visible) — then the alarm list is the whole device's.
const NO_TANK = -1;

Page({
  data: {
    device: '',
    // false while the station has no device this user may see (replacement not assigned to them, or the
    // device left their scope): the tabs are hidden and `notice` says why. Nothing is queried then.
    deviceActive: true,
    notice: '',
    // Informational line that does not stop the page (old stock link moved, a tank no longer visible).
    infoNotice: '',
    text: texts('detail'),
    tabs: tabsView(TABS),
    activeTab: 'snapshot' as TabKey,
    loading: false,
    liveAlarm: '',
    // The live-alarm connection is down and being retried: nothing arrives meanwhile, which must not read as
    // "no alarms".
    socketDown: false,
    // Per tab: '' = what is shown is current; otherwise the notice that the last refresh failed and the
    // list on screen may be out of date.
    stale: { snapshot: '', alarms: '', commands: '' } as Record<string, string>,
    // First load failed (nothing to keep on screen): the reason, with a retry button.
    snapshotError: '',
    alarmsError: '',

    snapshotTs: '',
    // The current tank's card only (an empty list when no tank is selected).
    tanks: [] as TankView[],
    snapshotLoaded: false,
    // Visible tanks of this device, for the switcher; currentTank is a protocol tank number or NO_TANK.
    tankOptions: [] as { tank: number; label: string }[],
    currentTank: NO_TANK,
    currentTankLabel: '',
    // Why there is no tank to pick (never reported / none visible); '' when there is one.
    tankless: '',

    // Time range of the 报警记录 tab. Presets roll back from "now" at load time; custom is whole local
    // days. rangeHint says why nothing is shown yet (e.g. dates not picked).
    rangePresets: RANGE_PRESETS.map((p) => ({ key: p.key, label: tr(p.labelKey) })),
    rangeKey: '24h' as RangeKey,
    customStart: '',
    customEnd: '',
    today: formatDate(new Date()),
    rangeHint: '',

    alarms: [] as AlarmView[],
    alarmsLoaded: false,
    alarmsFooter: '' as Footer,

    commands: [] as CommandView[],
    commandsLoaded: false,
  },

  socket: null as AlarmSocket | null,
  // Identity of "the device this page is showing". Bumped on every switch; every request and the
  // socket callback capture it and drop their result if it moved on, so nothing that was asked for the
  // old device can write into the new device's view.
  deviceGen: 0,
  snapshotSeq: 0,
  commandsSeq: 0,
  // The station this page was opened from (contract v1.2 §8.1): a number only when opened from a
  // station entry on the home page; the device then follows the station's current device
  // (utils/stationDevice.ts followStationDevice). Opened by device name alone (scan) it stays
  // undefined and the page is an independent view of that device: it never switches, and only checks
  // the device is still in this user's scope.
  stationId: undefined as number | null | undefined,
  associationBusy: false,
  unloaded: false,
  // Set while the entry has not yet produced a device (see retryEntry). `scene` is the code to locate.
  entryPending: null as 'scene' | 'station' | null,
  scene: '',
  commandsPollTimer: null as ReturnType<typeof setTimeout> | null,
  // Phone clock at the last successful read of the snapshot, for the "out of date since" notice.
  lastOkSnapshot: '',
  // The page was hidden (another page opened, or the app went to the background): coming back refreshes.
  wasHidden: false,
  stopNet: null as (() => void) | null,
  // The tank the entry asked for (home tank row). Used once, by the first snapshot of the first device:
  // after a device switch the new device's tanks are another set of objects and it starts at its first.
  requestedTank: undefined as number | undefined,
  // customer_user: the grants on this device from GET /api/devices (a second guard; the cloud already
  // filters). null = not known or an administrator.
  grantedTanks: null as number[] | null,
  // Tanks the cloud refused (403 tank_not_authorized) since this device was opened: left out of the
  // switcher even if a snapshot still lists them.
  revokedTanks: [] as number[],
  alarmFeed: null as RecordFeed<Alarm> | null,
  // Every tank of the last snapshot (the card shows the selected one only).
  allTanks: [] as TankView[],
  neverReported: false,

  onLoad(query: Record<string, string>) {
    // Network back: the live-alarm connection retries at once and the tab on screen is read again.
    this.stopNet = onNetworkBack(() => {
      this.socket?.reconnectNow();
      this.refreshActiveTab();
    });
    this.alarmFeed = new RecordFeed<Alarm>(
      'alarm',
      (s) => this.showAlarms(s),
      (err) => this.onTankRefused(err),
    );
    const device = query.device || '';
    // A view-only account (customer_user) gets no 控制命令 tab: it cannot issue commands and the cloud
    // does not show it the command history either (tank-grants-contract v1.0 §2).
    const tabs = isReadOnlyRole(roleFromToken(getToken())) ? TABS.filter((x) => x.key !== 'commands') : TABS;
    const requestedTab = query.tab as TabKey | undefined;
    const tab: TabKey = tabs.some((x) => x.key === requestedTab) ? (requestedTab as TabKey) : 'snapshot';
    this.requestedTank = parseTankParam(query.tank);
    this.stationId = stationIdFromKey(query.station);
    this.setData({ device, tabs: tabsView(tabs), activeTab: tab, today: formatDate(new Date()) });
    wx.setNavigationBarTitle({ title: device || tr('detail.navTitle') });
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
      this.setData({ deviceActive: false, notice: tr('detail.invalidEntry') });
      return;
    }
    // An old link to the retired 出入库记录 tab (the 我的记录 page used to open it): move to that station's
    // record list when the station can be verified, otherwise stay here and say where the records went.
    if (query.tab === 'oilInOut') {
      this.moveOldStockLink(device, query.direction === 'OUT' ? 'out' : 'in', entry.kind === 'station');
      return;
    }
    this.startEntry(entry.kind === 'station');
  },

  startEntry(byStation: boolean) {
    if (byStation) {
      // The device name on the query is only what the home list showed a moment ago: the station may
      // have been replaced since. Decide first, then query and subscribe.
      this.entryPending = 'station';
      this.enterByStation();
      return;
    }
    const device = this.data.device as string;
    this.loadTabIfNeeded(this.data.activeTab);
    this.openSocket(device);
    // Catch a replacement that happened between the home list being drawn and this page opening.
    this.refreshAssociation();
  },

  moveOldStockLink(device: string, type: 'in' | 'out', byStation: boolean) {
    const stationId = this.stationId;
    const stay = () => {
      this.setData({ infoNotice: tr('detail.oilMoved') });
      this.startEntry(byStation);
    };
    if (typeof stationId !== 'number' || !device) {
      stay();
      return;
    }
    get<{ rows: Device[] }>('/api/devices')
      .then((res) => {
        if (this.unloaded) return;
        if (followStationDevice(res.rows, device, stationId).kind === 'same') {
          wx.redirectTo({ url: `/pages/record-list/record-list?type=${type}&station=${stationId}` });
        } else {
          stay();
        }
      })
      .catch(() => {
        if (!this.unloaded) stay();
      });
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
        this.setData({
          loading: false,
          deviceActive: false,
          notice: `${describeResolveError(err)} ${tr('detail.retryHint')}`,
        });
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
    // The tapped tank belongs to the tapped device: keep it only if the station still shows that device.
    const requested = this.requestedTank;
    return get<{ rows: Device[] }>('/api/devices')
      .then((res) => {
        if (gen !== this.deviceGen) return;
        this.entryPending = null;
        const decision = followStationDevice(res.rows, device, this.stationId);
        if (decision.kind === 'none') {
          this.switchDevice('', tr(FOLLOW_NOTICES[decision.reason]));
        } else {
          const next = decision.kind === 'switch' ? decision.device : device;
          this.switchDevice(next, '', res.rows);
          if (next === device) this.requestedTank = requested;
        }
      })
      .catch((err: Error) => {
        if (gen !== this.deviceGen) return;
        this.setData({
          loading: false,
          deviceActive: false,
          notice: `${describeError(err)} ${tr('detail.retryHint')}`,
        });
      })
      .finally(() => {
        this.associationBusy = false;
      });
  },

  // One socket per device. A socket's own messages are checked against the generation it was opened
  // for, so a push that was already in flight when the device switched is dropped.
  openSocket(device: string) {
    const gen = this.deviceGen;
    const socket = new AlarmSocket(
      device,
      (msg: AlarmPushMessage) => {
        if (gen !== this.deviceGen || socket !== this.socket) return;
        // Another tank's alarm is not this page's: switching to that tank reloads its list anyway.
        const tank = this.data.currentTank as number;
        if (tank !== NO_TANK && msg.tank !== tank) return;
        this.setData({ liveAlarm: `${label(ALARM_TYPE_KEYS, msg.type)}: ${msg.msg}` });
        // A live push means the alarm list is stale if the user is looking at it.
        // Only when the list is the live tail: a rolling range, and the user has not paged down (a reload
        // would throw their place away). Otherwise the banner above is the notification.
        const feed = this.alarmFeed;
        if (this.data.activeTab === 'alarms' && this.data.rangeKey !== 'custom' && feed && feed.pageCount() <= 1) {
          this.loadAlarms();
        }
      },
      (state: SocketState) => {
        if (gen !== this.deviceGen || socket !== this.socket) return;
        this.setData({ socketDown: state === 'down' });
      },
    );
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
          this.switchDevice(decision.device, '', devices);
        } else if (decision.kind === 'none') {
          const notice = tr(FOLLOW_NOTICES[decision.reason]);
          // Already showing this notice with nothing held: nothing to clear again.
          if (this.data.device || this.data.notice !== notice) this.switchDevice('', notice);
        } else {
          this.grantedTanks = grantsOf(devices, device);
        }
      })
      // A failed re-read changes nothing: the current device stays as it was.
      .catch(() => undefined)
      .finally(() => {
        this.associationBusy = false;
      });
  },

  // Everything held for the old device is dropped here — latest value, tanks and the selected tank,
  // alarms, commands, live banner, paging state, the socket and the poll timer — and requests still in
  // flight for it are invalidated, before anything is asked for the new one. `device` '' = show the notice
  // and query nothing. Nothing is migrated: "old device + tank 1" and "new device + tank 1" are different
  // objects, so the new device's view starts empty, at its own first tank.
  switchDevice(device: string, notice: string, devices?: Device[]) {
    if (this.unloaded) return;
    this.deviceGen++;
    this.snapshotSeq++;
    this.commandsSeq++;
    this.alarmFeed?.reset();
    this.requestedTank = undefined;
    this.revokedTanks = [];
    this.allTanks = [];
    this.neverReported = false;
    this.grantedTanks = devices ? grantsOf(devices, device) : null;
    this.stopCommandsPoll();
    this.socket?.close();
    this.socket = null;
    this.lastOkSnapshot = '';
    this.setData({
      device,
      deviceActive: !!device,
      notice,
      loading: false,
      liveAlarm: '',
      socketDown: false,
      stale: { snapshot: '', alarms: '', commands: '' },
      snapshotError: '',
      alarmsError: '',
      snapshotTs: '',
      tanks: [],
      snapshotLoaded: false,
      tankOptions: [],
      currentTank: NO_TANK,
      currentTankLabel: '',
      tankless: '',
      alarms: [],
      alarmsLoaded: false,
      alarmsFooter: '',
      commands: [],
      commandsLoaded: false,
      rangeHint: '',
    });
    wx.setNavigationBarTitle({ title: device || tr('detail.navTitle') });
    if (!device) return;
    this.loadTabIfNeeded(this.data.activeTab);
    this.openSocket(device);
  },

  onShow() {
    this.setData({ text: texts('detail') });
    // Back from another page (or the app was in the background): the back office may have replaced the
    // station's device meanwhile.
    this.refreshAssociation();
    // Back from another page or from the background: read again and bring the live connection back at once
    // (including coming back from issue-command after a submit, so the new command's initial state shows).
    if (this.wasHidden) {
      this.wasHidden = false;
      this.socket?.reconnectNow();
      this.refreshActiveTab();
    }
  },

  onHide() {
    this.wasHidden = true;
    this.stopCommandsPoll();
  },

  // Reads the tab on screen again (nothing happens for a tab not loaded yet or without a device).
  refreshActiveTab(): void {
    if (!this.data.deviceActive || !this.data.device) return;
    const tab = this.data.activeTab as TabKey;
    if (tab === 'snapshot' && this.data.snapshotLoaded) this.loadSnapshot();
    else if (tab === 'alarms' && this.data.alarmsLoaded) this.loadAlarms();
    else if (tab === 'commands' && this.data.commandsLoaded) this.loadCommands();
  },

  // The phone's clock now, for "last updated" notices.
  nowText(): string {
    return formatLocalTime(new Date().toISOString(), currentLocale());
  },

  onUnload() {
    this.stopNet?.();
    this.stopNet = null;
    setActiveScene(null);
    // Invalidate everything still in flight: a scene/station resolution that comes back after the page
    // is gone must not switchDevice (which would start requests and a reconnecting socket nobody closes).
    this.unloaded = true;
    this.deviceGen++;
    this.snapshotSeq++;
    this.commandsSeq++;
    this.alarmFeed?.dispose();
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
      commands: () => this.loadCommands(),
    };
    reload[this.data.activeTab as TabKey]().finally(() => wx.stopPullDownRefresh());
  },

  // ---- Tank selection ----

  // The snapshot decides which tanks exist and are visible; the selected one stays selected while it is
  // visible. When it is not (grant revoked, tank gone) the first visible tank is selected and the page says
  // so. Selecting a different tank restarts the alarm list (the range stays).
  applyTanks(all: TankView[], neverReported: boolean) {
    this.allTanks = all;
    this.neverReported = neverReported;
    const visible = visibleTanks(
      all.map((v) => v.index),
      this.grantedTanks,
    ).filter((n) => !this.revokedTanks.includes(n));
    const current = this.data.currentTank as number;
    const requested = current !== NO_TANK ? current : this.requestedTank;
    this.requestedTank = undefined;
    const pick = pickTank(visible, requested);
    const tank = pick.kind === 'tank' ? pick.tank : NO_TANK;
    const view = all.find((v) => v.index === tank);
    const changed = tank !== current;
    let tankless = '';
    if (pick.kind === 'none') tankless = neverReported || all.length === 0 ? tr('detail.noTankInfo') : tr('detail.noVisibleTank');
    this.setData({
      tankOptions: visible.map((n) => ({ tank: n, label: tr('tank.no', { n }) })),
      currentTank: tank,
      currentTankLabel: view ? `${view.tankNoLabel}　${view.oilType || '--'}` : '',
      tanks: view ? [view] : [],
      tankless,
      ...(pick.hidden !== undefined ? { infoNotice: tr('detail.tankHidden', { n: pick.hidden }) } : {}),
    });
    if (changed) this.restartAlarms();
  },

  onPickTank(e: WechatMiniprogram.BaseEvent) {
    const tank = Number(e.currentTarget.dataset.tank);
    if (tank === this.data.currentTank) return;
    this.setData({ currentTank: tank, liveAlarm: '', infoNotice: '' });
    // Re-render from the last snapshot (the next refresh reads it again), then restart the alarm list.
    this.applyTanks(this.allTanks, this.neverReported);
    this.restartAlarms();
  },

  // A tank-level refusal (403 tank_not_authorized) is not a dead session and not "no records": drop that
  // tank and select another visible one.
  onTankRefused(err: Error) {
    if (failureCode(err) !== 'tank_not_authorized') return;
    const tank = this.data.currentTank as number;
    if (tank === NO_TANK) return;
    this.revokedTanks = this.revokedTanks.concat(tank);
    this.setData({ currentTank: NO_TANK, infoNotice: tr('detail.tankRevoked', { n: tank }) });
    this.loadSnapshot();
  },

  // GET /api/devices/:device/telemetry/latest — the cloud answers the granted tanks only.
  loadSnapshot() {
    if (!this.data.device) return Promise.resolve();
    const device = this.data.device as string;
    const seq = ++this.snapshotSeq;
    this.setData({ loading: true });
    return get<LatestTelemetryResponse>(`/api/devices/${encodeURIComponent(device)}/telemetry/latest`)
      .then((res) => {
        if (seq !== this.snapshotSeq) return;
        this.setData({ snapshotTs: formatLocalTime(res.ts, currentLocale()), snapshotLoaded: true, snapshotError: '' });
        this.applyTanks(toTankViews(res, device), false);
        this.markSnapshotFresh();
      })
      .catch((err: ApiError) => {
        if (seq !== this.snapshotSeq) return;
        if (isNeverReported(err)) {
          // Authorized and provisioned, but no data — a real state, not an error. Also what a replaced
          // device's successor looks like until it reports (and yeweiyi-cloud#28 can answer 404 for a
          // device that stopped reporting): an empty state, never the previous device's readings.
          this.setData({ snapshotTs: '', snapshotLoaded: true, snapshotError: '' });
          this.applyTanks([], true);
          this.markSnapshotFresh();
        } else {
          // Nothing was ever loaded: say that reading failed (not "no data"), with a retry. Something was: keep
          // it and mark it out of date.
          if (this.data.snapshotLoaded) {
            this.setData({ 'stale.snapshot': tr('detail.stale', { time: this.lastOkSnapshot || this.nowText() }) });
          } else {
            this.setData({ snapshotError: err.status === 403 ? tr('detail.noAccess') : describeError(err) });
          }
          // 403 here is "not authorized for this device": no assignment for this device. Say what to do
          // instead of the cloud's English text; nothing in the app can grant it.
          wx.showToast({
            title: err.status === 403 ? tr('detail.noAccess') : describeError(err),
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

  markSnapshotFresh() {
    this.lastOkSnapshot = this.nowText();
    this.setData({ 'stale.snapshot': '' });
  },

  // ---- Time range ----

  currentRange(): RecordsRange {
    return { key: this.data.rangeKey, startDate: this.data.customStart, endDate: this.data.customEnd };
  },

  // The window to query for a fresh load, or null when the range cannot be queried yet (custom dates not
  // picked or inconsistent) — then rangeHint says why and the list stays empty.
  windowForReload(): QueryWindow | null {
    const range = this.currentRange();
    const now = new Date();
    const problem = customRangeProblem(range, now);
    this.setData({ rangeHint: problem ? tr(RANGE_HINT_KEYS[problem]) : '' });
    return problem ? null : resolveWindow(range, now);
  },

  onPickRange(e: WechatMiniprogram.BaseEvent) {
    const key = e.currentTarget.dataset.key as RangeKey;
    if (key === this.data.rangeKey) return;
    // `today` bounds the date pickers; recompute it, the page may have been open past midnight.
    this.setData({ rangeKey: key, today: formatDate(new Date()) });
    this.restartAlarms();
  },

  onStartDate(e: WechatMiniprogram.CustomEvent) {
    this.setData({ customStart: e.detail.value as string });
    this.restartAlarms();
  },

  onEndDate(e: WechatMiniprogram.CustomEvent) {
    this.setData({ customEnd: e.detail.value as string });
    this.restartAlarms();
  },

  // A different window or tank: the list starts over and responses still in flight for the old one are
  // dropped. It loads now only when the tab is on screen, otherwise when it is opened.
  restartAlarms() {
    this.alarmFeed?.reset();
    if (this.data.activeTab === 'alarms') this.loadAlarms();
  },

  // ---- 报警记录 ----

  showAlarms(s: FeedState<Alarm>) {
    this.setData({
      alarms: s.rows.map(toAlarmView),
      alarmsLoaded: s.loaded,
      alarmsError: s.error,
      alarmsFooter: s.footer,
      'stale.alarms': s.stale,
      loading: s.loading,
    });
  },

  // The current tank's alarms (the whole device's when there is no tank to select). The tank is known only
  // after the snapshot: until then the snapshot is read first.
  loadAlarms(): Promise<unknown> {
    const feed = this.alarmFeed;
    const device = this.data.device as string;
    if (!feed || !device) return Promise.resolve();
    if (!this.data.snapshotLoaded) {
      // applyTanks restarts the list once a tank is selected; with none, load the whole device here.
      return this.loadSnapshot().then(() => {
        if (this.data.snapshotLoaded && this.data.currentTank === NO_TANK) return this.reloadAlarmFeed();
        if (!this.data.snapshotLoaded) this.setData({ alarmsError: this.data.snapshotError });
        return undefined;
      });
    }
    return this.reloadAlarmFeed();
  },

  reloadAlarmFeed(): Promise<unknown> {
    const feed = this.alarmFeed;
    const win = this.windowForReload();
    if (!feed || !win) return Promise.resolve();
    const tank = this.data.currentTank as number;
    return feed.reload({ device: this.data.device, win, filter: tank === NO_TANK ? {} : { tank } });
  },

  // Scrolled to the bottom of the page: the alarm tab asks for its next page.
  onReachBottom() {
    if (this.data.activeTab === 'alarms') this.alarmFeed?.loadMore();
  },

  // Tap on the footer (加载失败，点击重试 / 点击加载更多).
  onRetryMore() {
    if (this.data.activeTab === 'alarms') this.alarmFeed?.retryMore();
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
        wx.showToast({ title: describeError(err), icon: 'none' });
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

  // Control commands stay device-level: selecting a tank does not change their target or who may send them.
  onIssueCommand() {
    // Carry the station this page follows, so the form can check the device is still the station's
    // current one right before it sends (a replacement may happen while the form is open).
    const station = typeof this.stationId === 'number' ? `&station=${this.stationId}` : '';
    wx.navigateTo({
      url: `/pages/issue-command/issue-command?device=${encodeURIComponent(this.data.device)}${station}`,
    });
  },
});

// customer_user rows carry the tanks granted on that device; null for administrators or when not known.
function grantsOf(devices: Device[], device: string): number[] | null {
  const row = devices.find((d) => d.device_name === device);
  return row && Array.isArray(row.granted_tanks) ? row.granted_tanks : null;
}

export {};
