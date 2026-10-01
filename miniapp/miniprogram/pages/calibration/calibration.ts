const STORAGE_KEY = 'calibration-draft';

Page({
  data: {
    tankOptions: ['1号罐', '2号罐', '3号罐', '4号罐'],
    tankIndex: 0,
    levelOffset: '0',
    waterOffset: '0',
    tempOffset: '0',
  },

  onLoad() {
    const saved = wx.getStorageSync(STORAGE_KEY);
    if (saved) {
      this.setData(saved);
    }
  },

  onTankChange(e: WechatMiniprogram.PickerChange) {
    this.setData({ tankIndex: Number(e.detail.value) });
  },
  onLevelInput(e: WechatMiniprogram.Input) {
    this.setData({ levelOffset: e.detail.value });
  },
  onWaterInput(e: WechatMiniprogram.Input) {
    this.setData({ waterOffset: e.detail.value });
  },
  onTempInput(e: WechatMiniprogram.Input) {
    this.setData({ tempOffset: e.detail.value });
  },

  onSaveLocal() {
    const { tankIndex, levelOffset, waterOffset, tempOffset } = this.data;
    wx.setStorageSync(STORAGE_KEY, { tankIndex, levelOffset, waterOffset, tempOffset });
    wx.showToast({ title: '已保存到本机，不会下发给设备', icon: 'none' });
  },

  onSaveDevice() {
    wx.showToast({ title: '协议里没有校准类下发方法，暂不支持', icon: 'none', duration: 3000 });
  },
});

export {};
