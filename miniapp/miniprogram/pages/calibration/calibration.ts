const STORAGE_KEY = 'calibration-draft';

Page({
  data: {
    // 设计稿的罐号选择是 1–12 号
    tankOptions: Array.from({ length: 12 }, (_, i) => `${i + 1}号`),
    tankIndex: -1,
    levelOffset: '',
    waterOffset: '',
    tempOffset: '',
    pickerShow: false,
    pickerTop: 0,
  },

  onLoad() {
    const saved = wx.getStorageSync(STORAGE_KEY);
    if (saved) {
      this.setData(saved);
    }
  },

  onOpenTank() {
    // 面板贴在被点的那一行下面：量出这一行的 bottom 再弹出
    wx.createSelectorQuery()
      .select('#row-tank')
      .boundingClientRect((rect) => {
        if (rect) this.setData({ pickerShow: true, pickerTop: rect.bottom });
      })
      .exec();
  },
  onPickTank(e: WechatMiniprogram.CustomEvent<{ index: number }>) {
    this.setData({ tankIndex: e.detail.index, pickerShow: false });
  },
  onClosePicker() {
    this.setData({ pickerShow: false });
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
