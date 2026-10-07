import { ApiError, get } from '../../utils/request';
import { AlarmSocket } from '../../utils/ws';
import { roundFields } from '../../utils/format';
import { isOilInOutListResponse, summarizeOilInOut } from '../../utils/oilInOut';
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
type AlarmView = Alarm & { typeLabel: string; severityLabel: string; statusLabel: string };
type OilInOutView = OilInOutRow & { directionLabel: string };
type CommandView = ControlCommand & { methodLabel: string };

function toAlarmView(a: Alarm): AlarmView {
  return {
    ...a,
    typeLabel: label(ALARM_TYPE_LABELS, a.type),
    severityLabel: label(ALARM_SEVERITY_LABELS, a.severity),
    statusLabel: label(ALARM_STATUS_LABELS, a.status),
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
  return { ...roundFields(r, OIL_IN_OUT_NUMBER_FIELDS), directionLabel: label(STOCK_DIRECTION_LABELS, r.direction) };
}

function toCommandView(c: ControlCommand): CommandView {
  return { ...c, methodLabel: controlMethodLabel(c.method) };
}

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

    alarms: [] as AlarmView[],
    alarmsLoaded: false,

    oilInOut: [] as OilInOutView[],
    // Set whenever the endpoint reports it could not show everything — unparsable (current device
    // fault) or legacySkipped (closed historical hole) — so the list is never silently incomplete.
    // See yeweiyi-cloud docs/protocol-v1-stock-event.md.
    oilInOutNote: '',
    oilInOutLoaded: false,

    commands: [] as CommandView[],
    commandsLoaded: false,
  },

  socket: null as AlarmSocket | null,
  commandsPollTimer: null as ReturnType<typeof setTimeout> | null,
  // Set from the `direction` query param when arriving from the 我的记录 page's 出库/入库 entries
  // (device-detail itself only has one combined 出入库记录 tab; those two rows are the same tab
  // pre-filtered by direction).
  oilInOutDirection: '' as '' | 'IN' | 'OUT',

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
      if (this.data.activeTab === 'alarms') {
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

  // GET /api/alarms answers a bare array, not {rows: [...]} — see types/api.d.ts's note on Alarm.
  loadAlarms() {
    this.setData({ loading: true });
    return get<Alarm[]>('/api/alarms', { device: this.data.device, limit: 50, sort: false })
      .then((rows) => {
        this.setData({ alarms: rows.map(toAlarmView), alarmsLoaded: true });
      })
      .catch((err: Error) => wx.showToast({ title: err.message, icon: 'none' }))
      .finally(() => this.setData({ loading: false }));
  },

  // GET /api/oil-in-out answers {rows, parsed, unparsable, legacySkipped, ...} on BOTH its 200 and its
  // 502 path (internal/api/stock.go, stockListResponse) — unlike /api/alarms, which is a bare array.
  loadOilInOut() {
    this.setData({ loading: true });
    return get<OilInOutListResponse>('/api/oil-in-out', {
      device: this.data.device,
      limit: 50,
      sort: false,
    })
      .then((res) => this.applyOilInOut(res))
      .catch((err: ApiError) => {
        // A 502 here is the endpoint's own degraded-but-informative response, not a transport
        // failure — request.ts hands the full parsed body back on ApiError.body precisely so this
        // case does not collapse to a bare error toast and lose the rows that DID parse. Fall back
        // to the toast only if the body genuinely isn't that shape (a real outage, a 503, etc).
        if (isOilInOutListResponse(err.body)) {
          this.applyOilInOut(err.body);
        } else {
          wx.showToast({ title: err.message, icon: 'none' });
          this.setData({ oilInOut: [], oilInOutNote: err.message, oilInOutLoaded: true });
        }
      })
      .finally(() => this.setData({ loading: false }));
  },

  applyOilInOut(res: OilInOutListResponse) {
    if (!isOilInOutListResponse(res)) {
      // The response is not the shape this page was written against. Say so rather than throw.
      const message = '出入库记录的响应格式不符合预期，请联系管理员';
      wx.showToast({ title: message, icon: 'none' });
      this.setData({ oilInOut: [], oilInOutNote: message, oilInOutLoaded: true });
      return;
    }
    const { rows, note } = summarizeOilInOut(res, this.oilInOutDirection);
    this.setData({ oilInOut: rows.map(toOilInOutView), oilInOutNote: note, oilInOutLoaded: true });
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
