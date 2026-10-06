import { ApiError, get } from '../../utils/request';
import { getToken } from '../../utils/storage';
import { scanAndBind } from '../../utils/bind';
import { isNeverReported, toTankViews, type TankView } from '../../utils/telemetry';
import type { Device, LatestTelemetryResponse } from '../../types/api';

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
    showSingleDeviceHeader: false,
  },

  // Bumped on every reload; a telemetry response from an older reload is dropped instead of
  // overwriting a newer one (pull-to-refresh while a slow request is still out).
  loadSeq: 0,

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

  // GET /api/devices answers only the devices this user has bound (yeweiyi-cloud CLAUDE.md), then
  // one GET .../telemetry/latest per device, in parallel — there is no batch endpoint.
  loadHome(): Promise<unknown> {
    const seq = ++this.loadSeq;
    return get<{ rows: Device[] }>('/api/devices')
      .then((res) => {
        if (seq !== this.loadSeq) return;
        const groups = res.rows.map((d) => emptyGroup(d.device_name));
        this.setData({
          groups,
          devicesLoaded: true,
          listError: '',
          showSingleDeviceHeader: groups.length > 1,
        });
        return Promise.all(groups.map((g) => this.loadDevice(g.deviceName, seq)));
      })
      .catch((err: Error) => {
        if (seq !== this.loadSeq) return;
        this.setData({ devicesLoaded: true, listError: err.message });
        wx.showToast({ title: err.message, icon: 'none' });
      });
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
