import { ApiError, get } from '../../utils/request';
import { AlarmSocket, type AlarmPushMessage } from '../../utils/ws';
import type { Alarm, OilCanSnapshot, OilInOut, Shift, WriteBackCommand } from '../../types/api';

type TabKey = 'snapshot' | 'alarms' | 'oilInOut' | 'shifts' | 'writeBack';

const TABS: { key: TabKey; label: string }[] = [
  { key: 'snapshot', label: '实时数据' },
  { key: 'alarms', label: '报警记录' },
  { key: 'oilInOut', label: '卸油记录' },
  { key: 'shifts', label: '交班记录' },
  { key: 'writeBack', label: '下发记录' },
];

Page({
  data: {
    device: '',
    tabs: TABS,
    activeTab: 'snapshot' as TabKey,
    loading: false,
    liveAlarm: '',

    snapshot: [] as OilCanSnapshot[],
    snapshotLoaded: false,
    alarms: [] as Alarm[],
    alarmsLoaded: false,
    oilInOut: [] as OilInOut[],
    oilInOutLoaded: false,
    shifts: [] as Shift[],
    shiftsLoaded: false,
    writeBack: [] as WriteBackCommand[],
    writeBackLoaded: false,
  },

  socket: null as AlarmSocket | null,

  onLoad(query: Record<string, string>) {
    const device = query.device || '';
    this.setData({ device });
    wx.setNavigationBarTitle({ title: device || '设备详情' });
    this.loadSnapshot();

    this.socket = new AlarmSocket(device, (msg: AlarmPushMessage) => {
      this.setData({ liveAlarm: msg.msg });
      // A live push means the alarm list is stale if the user is looking at it.
      if (this.data.activeTab === 'alarms') {
        this.loadAlarms();
      }
    });
    this.socket.connect();
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
    if (tab === 'shifts' && !this.data.shiftsLoaded) this.loadShifts();
    if (tab === 'writeBack' && !this.data.writeBackLoaded) this.loadWriteBack();
  },

  loadSnapshot() {
    this.setData({ loading: true });
    get<OilCanSnapshot[]>('/api/oilcan/latest', { device: this.data.device })
      .then((rows) => {
        this.setData({ snapshot: rows, snapshotLoaded: true });
      })
      .catch((err: ApiError) => {
        if (err.status === 404) {
          this.setData({ snapshot: [], snapshotLoaded: true });
        } else {
          wx.showToast({ title: err.message, icon: 'none' });
        }
      })
      .finally(() => this.setData({ loading: false }));
  },

  loadAlarms() {
    this.setData({ loading: true });
    get<{ rows: Alarm[] }>('/api/alarms', { device: this.data.device, limit: 50, sort: false })
      .then((res) => this.setData({ alarms: res.rows, alarmsLoaded: true }))
      .catch((err: Error) => wx.showToast({ title: err.message, icon: 'none' }))
      .finally(() => this.setData({ loading: false }));
  },

  loadOilInOut() {
    this.setData({ loading: true });
    get<{ rows: OilInOut[] }>('/api/oil-in-out', { device: this.data.device, limit: 50, sort: false })
      .then((res) => this.setData({ oilInOut: res.rows, oilInOutLoaded: true }))
      .catch((err: Error) => wx.showToast({ title: err.message, icon: 'none' }))
      .finally(() => this.setData({ loading: false }));
  },

  loadShifts() {
    this.setData({ loading: true });
    get<{ rows: Shift[] }>('/api/shifts', { device: this.data.device, limit: 50, sort: false })
      .then((res) => this.setData({ shifts: res.rows, shiftsLoaded: true }))
      .catch((err: Error) => wx.showToast({ title: err.message, icon: 'none' }))
      .finally(() => this.setData({ loading: false }));
  },

  loadWriteBack() {
    this.setData({ loading: true });
    get<{ rows: WriteBackCommand[] }>('/api/write-back', { device: this.data.device, limit: 50 })
      .then((res) => this.setData({ writeBack: res.rows, writeBackLoaded: true }))
      .catch((err: Error) => wx.showToast({ title: err.message, icon: 'none' }))
      .finally(() => this.setData({ loading: false }));
  },

  onCorrectOilInOut(e: WechatMiniprogram.BaseEvent) {
    const item = e.currentTarget.dataset.item as OilInOut;
    wx.navigateTo({
      url: `/pages/correct-oil-in-out/correct-oil-in-out?device=${encodeURIComponent(this.data.device)}&record=${encodeURIComponent(JSON.stringify(item))}`,
    });
  },

  onCorrectShift(e: WechatMiniprogram.BaseEvent) {
    const item = e.currentTarget.dataset.item as Shift;
    wx.navigateTo({
      url: `/pages/correct-shift/correct-shift?device=${encodeURIComponent(this.data.device)}&record=${encodeURIComponent(JSON.stringify(item))}`,
    });
  },

  onGoOilCanSet() {
    wx.navigateTo({ url: `/pages/oil-can-set/oil-can-set?device=${encodeURIComponent(this.data.device)}` });
  },
});
