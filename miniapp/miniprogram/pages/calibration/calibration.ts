import { showComingSoon } from '../../utils/comingSoon';
import { setNavTitle, t, texts } from '../../utils/i18n';

const STORAGE_KEY = 'calibration-draft';

Page({
  data: {
    text: texts('calibration'),
    form: texts('form'),
    // 设计稿的罐号选择是 1–12 号
    tankOptions: [] as string[],
    tankIndex: -1,
    levelOffset: '',
    waterOffset: '',
    tempOffset: '',
    pickerShow: false,
    pickerTop: 0,
  },

  onShow() {
    setNavTitle('calibration.navTitle');
    this.setData({
      text: texts('calibration'),
      form: texts('form'),
      tankOptions: Array.from({ length: 12 }, (_, i) => t('form.numbered', { n: i + 1 })),
    });
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
    wx.showToast({ title: t('form.savedLocal'), icon: 'none' });
  },

  onSaveDevice() {
    showComingSoon(); // 协议里没有校准类下发方法
  },
});

export {};
