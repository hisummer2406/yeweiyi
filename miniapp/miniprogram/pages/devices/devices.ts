import { get, post } from '../../utils/request';
import { getToken } from '../../utils/storage';
import { parseDevicePairingQr } from '../../utils/pairing';
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
        const pairing = parseDevicePairingQr(res.result);
        if (!pairing) {
          wx.showToast({ title: '二维码格式不正确，请联系管理员确认设备标签', icon: 'none' });
          return;
        }
        post('/api/devices/bind', { deviceName: pairing.deviceName, pairingToken: pairing.pairingToken })
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

  // 对应设计稿「首页-添加」画板里"+"按钮弹出的菜单：添加设备/邀请加入/员工管理
  onMoreActions() {
    wx.showActionSheet({
      itemList: ['添加设备', '邀请加入', '员工管理'],
      success: (res) => {
        if (res.tapIndex === 0) {
          this.onScanBind();
        } else {
          wx.navigateTo({ url: '/pages/org/org' });
        }
      },
    });
  },
});
