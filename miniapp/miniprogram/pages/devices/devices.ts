import { get, post } from '../../utils/request';
import { getToken } from '../../utils/storage';
import type { Device } from '../../types/api';

Page({
  data: {
    devices: [] as Device[],
    loading: false,
  },

  onShow() {
    if (!getToken()) {
      wx.reLaunch({ url: '/pages/login/login' });
      return;
    }
    this.loadDevices();
  },

  onPullDownRefresh() {
    this.loadDevices().finally(() => wx.stopPullDownRefresh());
  },

  loadDevices() {
    this.setData({ loading: true });
    return get<{ rows: Device[] }>('/api/devices')
      .then((res) => {
        this.setData({ devices: res.rows });
      })
      .catch((err: Error) => {
        wx.showToast({ title: err.message, icon: 'none' });
      })
      .finally(() => {
        this.setData({ loading: false });
      });
  },

  onScanBind() {
    wx.scanCode({
      onlyFromCamera: false,
      success: (res) => {
        const deviceName = res.result;
        post('/api/devices/bind', { deviceName })
          .then(() => {
            wx.showToast({ title: '绑定成功', icon: 'success' });
            this.loadDevices();
          })
          .catch((err: Error) => {
            wx.showToast({ title: err.message, icon: 'none' });
          });
      },
      fail: () => {
        // user cancelled the scan; nothing to do
      },
    });
  },

  onTapDevice(e: WechatMiniprogram.BaseEvent) {
    const device = e.currentTarget.dataset.device as string;
    wx.navigateTo({ url: `/pages/device-detail/device-detail?device=${encodeURIComponent(device)}` });
  },
});
