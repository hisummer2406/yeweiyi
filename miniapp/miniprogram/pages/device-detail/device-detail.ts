import { ApiError, get } from '../../utils/request';
import { AlarmSocket } from '../../utils/ws';
import {
  ALARM_SEVERITY_LABELS,
  ALARM_STATUS_LABELS,
  ALARM_TYPE_LABELS,
  STOCK_DIRECTION_LABELS,
  TANK_ALARM_STATE_LABELS,
  controlMethodLabel,
  label,
} from '../../utils/labels';
import type {
  Alarm,
  AlarmPushMessage,
  ControlCommand,
  LatestTelemetryResponse,
  ListCommandsResponse,
  OilInOutRow,
  Tank,
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
type TankView = Tank & { alarmStateLabel: string };
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

function toOilInOutView(r: OilInOutRow): OilInOutView {
  return { ...r, directionLabel: label(STOCK_DIRECTION_LABELS, r.direction) };
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
    oilInOutLoaded: false,

    commands: [] as CommandView[],
    commandsLoaded: false,
  },

  socket: null as AlarmSocket | null,
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

  onUnload() {
    this.socket?.close();
    this.socket = null;
  },

  onSwitchTab(e: WechatMiniprogram.BaseEvent) {
    const tab = e.currentTarget.dataset.tab as TabKey;
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
        const tanks: TankView[] = res.tanks.map((t) => ({
          ...t,
          alarmStateLabel: label(TANK_ALARM_STATE_LABELS, t.alarmState),
        }));
        this.setData({ snapshotTs: res.ts, tanks, snapshotLoaded: true });
      })
      .catch((err: ApiError) => {
        if (err.status === 404) {
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

  // GET /api/oil-in-out answers a bare array, same as /api/alarms — internal/api/stock.go reads
  // through to ThingsBoard's stockEvent telemetry and silently skips any record it can't parse
  // (pre-V1 firmware still publishing debug strings) rather than reporting an unparsable/legacySkipped
  // count. There is no envelope to recover from a 502 body any more; an error here is a real failure.
  loadOilInOut() {
    this.setData({ loading: true });
    return get<OilInOutRow[]>('/api/oil-in-out', {
      device: this.data.device,
      limit: 50,
      sort: false,
    })
      .then((rows) => {
        const filtered = this.oilInOutDirection
          ? rows.filter((r) => r.direction === this.oilInOutDirection)
          : rows;
        this.setData({ oilInOut: filtered.map(toOilInOutView), oilInOutLoaded: true });
      })
      .catch((err: Error) => wx.showToast({ title: err.message, icon: 'none' }))
      .finally(() => this.setData({ loading: false }));
  },

  loadCommands() {
    this.setData({ loading: true });
    return get<ListCommandsResponse>(
      `/api/devices/${encodeURIComponent(this.data.device)}/commands`,
      { limit: 50 },
    )
      .then((res) => {
        this.setData({ commands: res.commands.map(toCommandView), commandsLoaded: true });
      })
      .catch((err: Error) => wx.showToast({ title: err.message, icon: 'none' }))
      .finally(() => this.setData({ loading: false }));
  },

  onIssueCommand() {
    wx.navigateTo({
      url: `/pages/issue-command/issue-command?device=${encodeURIComponent(this.data.device)}`,
    });
  },
});
