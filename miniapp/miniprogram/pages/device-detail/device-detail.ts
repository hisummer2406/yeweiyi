import { describeError } from '../../utils/errors';
import { currentLocale, t as tr, texts } from '../../utils/i18n';
import { onNetworkBack } from '../../utils/network';
import { ApiError, get } from '../../utils/request';
import { FOLLOW_NOTICES, followStationDevice, stationIdFromKey } from '../../utils/stationDevice';
import { AlarmSocket, type SocketState } from '../../utils/ws';
import { roleFromToken, userKeyFromToken } from '../../utils/auth';
import { isReadOnlyRole } from '../../utils/roles';
import { getToken } from '../../utils/storage';
import { describeResolveError, resolveDevice } from '../../utils/scan';
import { classifyEntry, loginUrlForScene, setActiveScene } from '../../utils/sceneEntry';
import { formatLocalTime } from '../../utils/format';
import { parseTankParam, pickTank, visibleTanks } from '../../utils/tankPick';
import { toTankViews, tankShapeFields, type TankView } from '../../utils/telemetry';
import { emptyTankShapes, tankShapeFailure, type TankShapeState } from '../../utils/tankShape';
import { getTankSettings } from '../../utils/remoteConfigApi';
import { telemetryFailure } from '../../utils/failureState';
import type { ParamEditorRef } from '../../utils/paramSwitch';
import {
  ALARM_TYPE_KEYS,
  COMMAND_STATUS_KEYS,
  controlMethodLabel,
  label,
} from '../../utils/labels';
import type {
  AlarmPushMessage,
  ControlCommand,
  Device,
  LatestTelemetryResponse,
  ListCommandsResponse,
} from '../../types/api';

// One tank at a time (miniapp#15): the home page opens the tank that was tapped, and the 实时数据 tab and
// the live-alarm banner follow the tank selected here. Records — alarms as well as stock in/out — are not
// on this page: they have one entry only, under 我的记录 (pages/record-list), bound to the home page's
// station. An old `tab=alarms` link simply opens 实时数据.
// 参数修正 / 储罐设置（#43）：同一设备、同一罐的编辑页签（components/param-editor）。页内没有罐切换：首页点哪个罐就是哪个罐。
type TabKey = 'snapshot' | 'calibration' | 'settings' | 'commands';

const EDITOR_TABS: TabKey[] = ['calibration', 'settings'];

const TABS: { key: TabKey; labelKey: string }[] = [
  { key: 'snapshot', labelKey: 'detail.tabSnapshot' },
  { key: 'calibration', labelKey: 'detail.tabCalibration' },
  { key: 'settings', labelKey: 'detail.tabSettings' },
  // 控制命令 tab 入口已于 2026-10-17 关闭（页面、轮询和 issue-command 都保留，没有别的入口能进）。
  // 想起来再开时把下面这项加回 TABS：
  // { key: 'commands', labelKey: 'detail.tabCommands' },
];

// What WXML renders: the tab list in the current language.
function tabsView(tabs: { key: TabKey; labelKey: string }[]): { key: TabKey; label: string }[] {
  return tabs.map((tab) => ({ key: tab.key, label: tr(tab.labelKey) }));
}

type CommandView = ControlCommand & { methodLabel: string; statusLabel: string };

function toCommandView(c: ControlCommand): CommandView {
  return { ...c, methodLabel: controlMethodLabel(c.method), statusLabel: label(COMMAND_STATUS_KEYS, c.status) };
}

// No tank selected: the snapshot has not been read yet, or the device has no tank to show (never
// reported, or none visible).
const NO_TANK = -1;

// Every reading of one tank in the snapshot (yeweiyi-cloud internal/protocol.Tank), as label / value / unit
// rows: the detail page shows all of them, the home card only five. Missing or non-numeric values are a
// dash, never 0. The tank number and oil type are in the header; snapshotSeq is a transport counter and
// is not shown.
type TankField = { label: string; value: string; unit: string; strong?: boolean };

function num(v: unknown): string {
  return typeof v === 'number' && Number.isFinite(v) ? String(v) : '—';
}

function tankFields(v: TankView): TankField[] {
  return [
    { label: tr('detail.level'), value: num(v.oilLevelMm), unit: tr('detail.unitMm'), strong: true },
    { label: tr('detail.volume'), value: num(v.oilVolumeL), unit: tr('detail.unitL'), strong: true },
    { label: tr('detail.mass'), value: num(v.oilWeightKg), unit: tr('detail.unitKg') },
    { label: tr('detail.temp'), value: num(v.tempC), unit: '℃' },
    { label: tr('detail.water'), value: num(v.waterLevelMm), unit: tr('detail.unitMm') },
    { label: tr('detail.waterVolume'), value: num(v.waterVolumeL), unit: tr('detail.unitL') },
    { label: tr('detail.ullage'), value: num(v.nullVolumeL), unit: tr('detail.unitL') },
    { label: tr('detail.oilRatio'), value: num(v.oilRatioPct), unit: '%' },
    { label: tr('detail.waterRatio'), value: num(v.waterRatioPct), unit: '%' },
  ];
}

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
    stale: { snapshot: '', commands: '' } as Record<string, string>,
    // First load failed (nothing to keep on screen): the reason, with a retry button.
    snapshotError: '',
    // Which empty state the snapshot tab is in, when it has no data (yeweiyi-cloud#79): '' = none.
    emptyNote: '',

    snapshotTs: '',
    // The current tank's card only (an empty list when no tank is selected).
    tanks: [] as TankView[],
    // All readings of the current tank, see tankFields.
    tankFields: [] as TankField[],
    snapshotLoaded: false,
    // Visible tanks of this device; currentTank (the tapped one, else the lowest) is a protocol tank number or NO_TANK.
    tankOptions: [] as { tank: number; label: string }[],
    currentTank: NO_TANK,
    currentTankLabel: '',
    // Why there is no tank to pick (never reported / none visible); '' when there is one.
    tankless: '',

    commands: [] as CommandView[],
    commandsLoaded: false,
    // The device row's station_id for the editors' submission check: -1 = on no station, -2 = not known yet.
    deviceStation: -2,
    // Info bar (#43): the station name the home page showed (display only; '' for a scan entry).
    stationName: '',
  },

  socket: null as AlarmSocket | null,
  // Identity of "the device this page is showing". Bumped on every switch; every request and the
  // socket callback capture it and drop their result if it moved on, so nothing that was asked for the
  // old device can write into the new device's view.
  deviceGen: 0,
  snapshotSeq: 0,
  shapeSeq: 0,
  shapeState: emptyTankShapes(),
  shapeAccount: '',
  shapeTimer: null as ReturnType<typeof setInterval> | null,
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
  // Every tank of the last snapshot (the card shows the selected one only).
  allTanks: [] as TankView[],
  neverReported: false,

  onLoad(query: Record<string, string>) {
    // Network back: the live-alarm connection retries at once and the tab on screen is read again.
    this.stopNet = onNetworkBack(() => {
      this.socket?.reconnectNow();
      this.refreshActiveTab();
    });
    const device = query.device || '';
    // A view-only account (customer_user) gets no 控制命令 tab: it cannot issue commands and the cloud
    // does not show it the command history either (tank-grants-contract v1.0 §2).
    const tabs = isReadOnlyRole(roleFromToken(getToken())) ? TABS.filter((x) => x.key !== 'commands') : TABS;
    const requestedTab = query.tab as TabKey | undefined;
    const tab: TabKey = tabs.some((x) => x.key === requestedTab) ? (requestedTab as TabKey) : 'snapshot';
    this.requestedTank = parseTankParam(query.tank);
    this.stationId = stationIdFromKey(query.station);
    const stationName = typeof this.stationId === 'number' && query.stationName ? decodeURIComponent(query.stationName) : '';
    this.setData({ device, tabs: tabsView(tabs), activeTab: tab, stationName });
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
        // Another tank's alarm is not this page's.
        const tank = this.data.currentTank as number;
        if (tank !== NO_TANK && msg.tank !== tank) return;
        this.setData({ liveAlarm: `${label(ALARM_TYPE_KEYS, msg.type)}: ${msg.msg}` });
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
          const deviceStation = stationOf(devices, device);
          if (deviceStation !== this.data.deviceStation) this.setData({ deviceStation });
        }
      })
      // A failed re-read changes nothing: the current device stays as it was.
      .catch(() => undefined)
      .finally(() => {
        this.associationBusy = false;
      });
  },

  // Everything held for the old device is dropped here — latest value, tanks and the selected tank,
  // commands, live banner, paging state, the socket and the poll timer — and requests still in
  // flight for it are invalidated, before anything is asked for the new one. `device` '' = show the notice
  // and query nothing. Nothing is migrated: "old device + tank 1" and "new device + tank 1" are different
  // objects, so the new device's view starts empty, at its own first tank.
  switchDevice(device: string, notice: string, devices?: Device[]) {
    if (this.unloaded) return;
    this.deviceGen++;
    this.snapshotSeq++;
    this.shapeSeq++;
    this.shapeState = emptyTankShapes();
    this.commandsSeq++;
    this.requestedTank = undefined;
    this.allTanks = [];
    this.neverReported = false;
    this.grantedTanks = devices ? grantsOf(devices, device) : null;
    const deviceStation = devices ? stationOf(devices, device) : -2;
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
      stale: { snapshot: '', commands: '' },
      snapshotError: '',
      emptyNote: '',
      snapshotTs: '',
      tanks: [],
      tankFields: [],
      snapshotLoaded: false,
      tankOptions: [],
      currentTank: NO_TANK,
      currentTankLabel: '',
      tankless: '',
      commands: [],
      commandsLoaded: false,
      deviceStation,
    });
    wx.setNavigationBarTitle({ title: device || tr('detail.navTitle') });
    if (!device) return;
    this.loadTabIfNeeded(this.data.activeTab);
    this.openSocket(device);
  },

  onShow() {
    this.setData({ text: texts('detail') });
    this.stopShapePoll();
    this.shapeTimer = setInterval(() => { if (this.data.deviceActive) void this.loadShapes(); }, 60_000);
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
    this.shapeSeq++;
    this.stopShapePoll();
    this.stopCommandsPoll();
  },

  stopShapePoll() {
    if (this.shapeTimer) clearInterval(this.shapeTimer);
    this.shapeTimer = null;
  },

  // Reads the tab on screen again (nothing happens for a tab not loaded yet or without a device).
  refreshActiveTab(): void {
    if (!this.data.deviceActive || !this.data.device) return;
    const tab = this.data.activeTab as TabKey;
    // The editors re-read the device's settings themselves (on show); the snapshot decides the tanks.
    if ((tab === 'snapshot' || EDITOR_TABS.includes(tab)) && this.data.snapshotLoaded) this.loadSnapshot();
    else if (tab === 'commands' && this.data.commandsLoaded) this.loadCommands();
  },

  // The phone's clock now, for "last updated" notices.
  nowText(): string {
    return formatLocalTime(new Date().toISOString(), currentLocale());
  },

  onUnload() {
    this.shapeSeq++;
    this.stopShapePoll();
    this.stopNet?.();
    this.stopNet = null;
    setActiveScene(null);
    // Invalidate everything still in flight: a scene/station resolution that comes back after the page
    // is gone must not switchDevice (which would start requests and a reconnecting socket nobody closes).
    this.unloaded = true;
    this.deviceGen++;
    this.snapshotSeq++;
    this.commandsSeq++;
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
    // The editor tabs need the visible tanks, which come with the snapshot.
    if ((tab === 'snapshot' || EDITOR_TABS.includes(tab)) && !this.data.snapshotLoaded) this.loadSnapshot();
    if (tab === 'commands' && !this.data.commandsLoaded) this.loadCommands();
  },

  onPullDownRefresh() {
    if (!this.data.deviceActive) {
      this.refreshAssociation().finally(() => wx.stopPullDownRefresh());
      return;
    }
    this.refreshAssociation();
    const editor = (tab: TabKey) => () => {
      const ed = this.editorOf(tab) as unknown as { refresh?: () => void } | null;
      ed?.refresh?.();
      return this.loadSnapshot();
    };
    const reload: Record<TabKey, () => Promise<unknown>> = {
      snapshot: () => this.loadSnapshot(),
      calibration: editor('calibration'),
      settings: editor('settings'),
      commands: () => this.loadCommands(),
    };
    reload[this.data.activeTab as TabKey]().finally(() => wx.stopPullDownRefresh());
  },

  // ---- Tank selection ----

  // The snapshot decides which tanks exist and are visible; the selected one stays selected while it is
  // visible. When it is not (grant revoked, tank gone) the first visible tank is selected and the page says
  // so.
  applyTanks(all: TankView[], neverReported: boolean) {
    this.allTanks = all;
    this.neverReported = neverReported;
    const visible = visibleTanks(
      all.map((v) => v.index),
      this.grantedTanks,
);
    const current = this.data.currentTank as number;
    const requested = current !== NO_TANK ? current : this.requestedTank;
    this.requestedTank = undefined;
    const pick = pickTank(visible, requested);
    const tank = pick.kind === 'tank' ? pick.tank : NO_TANK;
    const view = all.find((v) => v.index === tank);
    let tankless = '';
    if (pick.kind === 'none') tankless = neverReported || all.length === 0 ? tr('detail.noTankInfo') : tr('detail.noVisibleTank');
    this.setData({
      tankOptions: visible.map((n) => ({ tank: n, label: tr('tank.no', { n }) })),
      currentTank: tank,
      currentTankLabel: view ? `${view.tankNoLabel}　${view.oilType || '--'}` : '',
      tanks: view ? [view] : [],
      tankFields: view ? tankFields(view) : [],
      tankless,
      ...(pick.hidden !== undefined ? { infoNotice: tr('detail.tankHidden', { n: pick.hidden }) } : {}),
    });
  },

  editorOf(tab: TabKey): ParamEditorRef | null {
    return this.selectComponent(`#ed-${tab}`) as unknown as ParamEditorRef | null;
  },


  // GET /api/devices/:device/telemetry/latest — the cloud answers the granted tanks only.
  loadSnapshot() {
    if (!this.data.device) return Promise.resolve();
    const device = this.data.device as string;
    const seq = ++this.snapshotSeq;
    void this.loadShapes();
    this.setData({ loading: true });
    return get<LatestTelemetryResponse>(`/api/devices/${encodeURIComponent(device)}/telemetry/latest`)
      .then((res) => {
        if (seq !== this.snapshotSeq) return;
        this.setData({ snapshotTs: formatLocalTime(res.ts, currentLocale()), snapshotLoaded: true, snapshotError: '', emptyNote: '' });
        this.applyTanks(toTankViews(res, device, this.shapeState), false);
        this.markSnapshotFresh();
      })
      .catch((err: ApiError) => {
        if (seq !== this.snapshotSeq) return;
        const empty = telemetryFailure(err.status, err.body);
        if (empty.kind === 'empty') {
          // No data — a real state, not an error. Also what a replaced device's successor looks like until
          // it reports: an empty state, never the previous device's readings. Which one — never reported,
          // past the retention period (yeweiyi-cloud#28), not set up on the platform — is said (#79).
          const emptyNote = tr(empty.key, {
            time: empty.lastReportedAt ? formatLocalTime(empty.lastReportedAt, currentLocale()) : '—',
          });
          this.setData({ snapshotTs: '', snapshotLoaded: true, snapshotError: '', emptyNote });
          this.applyTanks([], true);
          this.markSnapshotFresh();
        } else {
          if ([401, 403].includes(err.status)) {
            this.shapeSeq++;
            this.shapeState = emptyTankShapes();
            this.applyTanks([], false);
            this.setData({ snapshotLoaded: false, snapshotTs: '' });
          }
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

  loadShapes(): Promise<void> {
    const device = this.data.device;
    if (!device || this.wasHidden) return Promise.resolve();
    const seq = ++this.shapeSeq;
    const account = userKeyFromToken(getToken());
    if (account !== this.shapeAccount) {
      this.shapeAccount = account;
      this.shapeState = emptyTankShapes();
      if (this.allTanks.length) this.applyTanks(this.allTanks.map((t) => ({ ...t, ...tankShapeFields(this.shapeState, t.index) })), this.neverReported);
    }
    const apply = (state: TankShapeState) => {
      if (seq !== this.shapeSeq || device !== this.data.device || account !== userKeyFromToken(getToken()) || this.unloaded) return;
      this.shapeState = state;
      if (this.allTanks.length) this.applyTanks(this.allTanks.map((t) => ({ ...t, ...tankShapeFields(state, t.index) })), this.neverReported);
    };
    return getTankSettings(device).then((rows) => apply({ rows, status: 'ok' })).catch((error: ApiError) => apply(tankShapeFailure(this.shapeState, error.status)));
  },

  markSnapshotFresh() {
    this.lastOkSnapshot = this.nowText();
    this.setData({ 'stale.snapshot': '' });
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

// The device row's station_id (-1 = on no station; -2 = the device is not in the list).
function stationOf(devices: Device[], device: string): number {
  const row = devices.find((d) => d.device_name === device);
  if (!row) return -2;
  return typeof row.station_id === 'number' ? row.station_id : -1;
}

// customer_user rows carry the tanks granted on that device; null for administrators or when not known.
function grantsOf(devices: Device[], device: string): number[] | null {
  const row = devices.find((d) => d.device_name === device);
  return row && Array.isArray(row.granted_tanks) ? row.granted_tanks : null;
}

export {};
