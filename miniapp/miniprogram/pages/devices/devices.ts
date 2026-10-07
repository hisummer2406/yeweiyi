import { ApiError, get } from '../../utils/request';
import { getToken } from '../../utils/storage';
import { scanAndBind } from '../../utils/bind';
import { isNeverReported, toTankViews, type TankView } from '../../utils/telemetry';
import { usernameFromToken } from '../../utils/auth';
import { getRememberedStation, rememberStation } from '../../utils/stationPref';
import {
  buildStationOptions,
  devicesOfStation,
  pickStation,
  type StationOption,
} from '../../utils/stations';
import type { Device, LatestTelemetryResponse, Station } from '../../types/api';

// One device's slice of the home page. `state` is per device on purpose: one device failing (or
// never having reported) must not blank the others.
type GroupState = 'loading' | 'ok' | 'never' | 'error';

type DeviceGroup = {
  deviceName: string;
  state: GroupState;
  ts: string;
  tanks: TankView[];
  error: string;
};

const emptyGroup = (deviceName: string): DeviceGroup => ({
  deviceName,
  state: 'loading',
  ts: '',
  tanks: [],
  error: '',
});

Page({
  data: {
    groups: [] as DeviceGroup[],
    // Distinguishes "still asking which devices I have" from "I have none", so the first-run empty
    // state does not flash while /api/devices is in flight.
    devicesLoaded: false,
    listError: '',
    // Station dropdown. hasStations is false when the cloud has no stations for this user, in which
    // case the home page is the flat list it was before stations existed.
    hasStations: false,
    stationName: '',
    stationKey: '',
    // All devices this user can see, regardless of the selected station. Distinguishes "no devices at
    // all" (first-run empty state: go scan) from "the selected station has none".
    deviceTotal: 0,
    showSingleDeviceHeader: false,
    // The header is drawn under the status bar (navigationStyle: custom), so it needs the status
    // bar's height in rpx. Falls back to the design draft's 88rpx if the API is unavailable.
    statusBarRpx: 88,
  },

  // Bumped on every reload; a telemetry response from an older reload is dropped instead of
  // overwriting a newer one (pull-to-refresh while a slow request is still out).
  loadSeq: 0,
  allDevices: [] as Device[],
  allStations: [] as Station[],
  stationOptions: [] as StationOption[],
  // The station the user picked on the select page during this page's lifetime. Kept in memory so the
  // choice survives even if the best-effort storage write failed. Logout / account switch goes through
  // reLaunch, which destroys the page, so this cannot leak between accounts.
  chosenStationKey: undefined as string | undefined,

  onLoad() {
    try {
      const info = wx.getWindowInfo();
      this.setData({ statusBarRpx: Math.round((info.statusBarHeight * 750) / info.windowWidth) });
    } catch {
      // keep the default
    }
  },

  onShow() {
    if (!getToken()) {
      wx.reLaunch({ url: '/pages/login/login' });
      return;
    }
    this.loadHome();
  },

  onPullDownRefresh() {
    this.loadHome().finally(() => wx.stopPullDownRefresh());
  },

  // GET /api/devices answers the devices this user may see, GET /api/stations the stations they sit
  // in (yeweiyi-cloud#53). Stations are an enhancement: an older cloud answers 404, and any failure
  // here just means "no dropdown" — it must never break the home page. Then one telemetry request per
  // device of the SELECTED station, in parallel — there is no batch endpoint.
  loadHome(): Promise<unknown> {
    const seq = ++this.loadSeq;
    return Promise.all([
      get<{ rows: Device[] }>('/api/devices'),
      get<{ rows: Station[] }>('/api/stations').catch(() => ({ rows: [] as Station[] })),
    ])
      .then(([devRes, stRes]) => {
        if (seq !== this.loadSeq) return;
        this.allDevices = devRes.rows;
        this.allStations = stRes.rows;
        this.stationOptions = buildStationOptions(stRes.rows, devRes.rows);
        return this.showStation(seq, this.chosenStationKey || getRememberedStation(this.currentUsername()));
      })
      .catch((err: Error) => {
        if (seq !== this.loadSeq) return;
        this.setData({ devicesLoaded: true, listError: err.message });
        wx.showToast({ title: err.message, icon: 'none' });
      });
  },

  currentUsername(): string {
    return usernameFromToken(getToken());
  },

  // Show one station's devices (or every device when there is no dropdown) and load their telemetry.
  showStation(seq: number, preferredKey?: string): Promise<unknown> {
    const options = this.stationOptions as StationOption[];
    const current = pickStation(options, preferredKey);
    const visible = current ? devicesOfStation(this.allDevices, this.allStations, current.key) : this.allDevices;
    const groups = visible.map((d: Device) => emptyGroup(d.device_name));
    this.setData({
      groups,
      devicesLoaded: true,
      listError: '',
      showSingleDeviceHeader: groups.length > 1,
      hasStations: options.length > 0,
      stationName: current ? current.name : '',
      stationKey: current ? current.key : '',
      deviceTotal: this.allDevices.length,
    });
    return Promise.all(groups.map((g: DeviceGroup) => this.loadDevice(g.deviceName, seq)));
  },

  onPickStation() {
    wx.navigateTo({
      url: '/pages/station-select/station-select',
      events: {
        selected: (key: string) => this.onStationSelected(key),
      },
      success: (res) => {
        res.eventChannel.emit('init', { options: this.stationOptions, current: this.data.stationKey });
      },
    });
  },

  onStationSelected(key: string) {
    // Only record the choice. The select page calls wx.navigateBack() right after emitting, which
    // always fires onShow -> loadHome(); reloading here too would load everything twice.
    this.chosenStationKey = key;
    rememberStation(this.currentUsername(), key);
  },

  loadDevice(deviceName: string, seq: number): Promise<void> {
    return get<LatestTelemetryResponse>(
      `/api/devices/${encodeURIComponent(deviceName)}/telemetry/latest`,
    )
      .then((res) => this.patchGroup(deviceName, seq, { state: 'ok', ts: res.ts, tanks: toTankViews(res), error: '' }))
      .catch((err: ApiError) => {
        if (isNeverReported(err)) {
          // Authorized and provisioned, but has never reported — a real state, not an error.
          this.patchGroup(deviceName, seq, { state: 'never', ts: '', tanks: [], error: '' });
        } else {
          this.patchGroup(deviceName, seq, { state: 'error', ts: '', tanks: [], error: err.message });
        }
      });
  },

  patchGroup(deviceName: string, seq: number, patch: Partial<DeviceGroup>) {
    if (seq !== this.loadSeq) return;
    const index = this.data.groups.findIndex((g: DeviceGroup) => g.deviceName === deviceName);
    if (index < 0) return;
    const update: Record<string, unknown> = {};
    for (const key of Object.keys(patch) as (keyof DeviceGroup)[]) {
      update[`groups[${index}].${key}`] = patch[key];
    }
    this.setData(update);
  },

  onRetryDevice(e: WechatMiniprogram.BaseEvent) {
    const deviceName = e.currentTarget.dataset.device as string;
    this.patchGroup(deviceName, this.loadSeq, { state: 'loading', error: '' });
    this.loadDevice(deviceName, this.loadSeq);
  },

  onTapTank(e: WechatMiniprogram.BaseEvent) {
    const device = e.currentTarget.dataset.device as string;
    wx.navigateTo({ url: `/pages/device-detail/device-detail?device=${encodeURIComponent(device)}` });
  },

  onScanBind() {
    scanAndBind(() => this.loadHome());
  },

  // 设计稿「首页-添加」：+ 号菜单。邀请加入本阶段不做（账号只在后台创建），油站管理暂未开放。
  onMoreActions() {
    wx.showActionSheet({
      itemList: ['添加设备', '油站管理'],
      success: (res) => {
        if (res.tapIndex === 0) {
          wx.navigateTo({ url: '/pages/device-add/device-add' });
        } else {
          wx.showToast({ title: '油站管理暂未开放', icon: 'none' });
        }
      },
    });
  },
});
