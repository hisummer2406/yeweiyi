// 设计稿里字母标签只有 H / L / R，没有文字说明：
// 卧罐 H（罐体直径）、L（中段长度）、R（封头半径）；立罐 H（高）、R（图上标在顶面整个宽度上，
// 是半径还是直径设计稿没说清）；长方体 H（高）、L（长）、R（图上标在进深方向，应为宽，设计稿用 R）。
// 这几处含义需要设计/工程确认，在此之前页面只显示字母，不替设计稿编含义。
import { showComingSoon } from '../../utils/comingSoon';
type Dim = { key: string; value: string };

const SHAPE_TITLES: Record<string, string> = { horizontal: '卧罐', vertical: '立罐', cuboid: '长方体' };
const DIM_KEYS: Record<string, string[]> = {
  horizontal: ['H', 'L', 'R'],
  vertical: ['H', 'R'],
  cuboid: ['H', 'L', 'R'],
};

const storageKey = (shape: string) => `tankshape-draft-${shape}`;

Page({
  data: {
    shape: 'horizontal',
    dims: [] as Dim[],
    // 设计稿的罐号选择是 1–12 号
    tankOptions: Array.from({ length: 12 }, (_, i) => `${i + 1}号`),
    tankIndex: -1,
    pickerShow: false,
    pickerTop: 0,
  },

  onLoad(query: Record<string, string>) {
    const shape = DIM_KEYS[query.shape] ? query.shape : 'horizontal';
    wx.setNavigationBarTitle({ title: SHAPE_TITLES[shape] });
    let saved: Record<string, string> = {};
    try {
      saved = wx.getStorageSync(storageKey(shape)) || {};
    } catch {
      // 读不到就当没有保存过
    }
    this.setData({
      shape,
      dims: DIM_KEYS[shape].map((key) => ({ key, value: saved[key] || '' })),
    });
  },

  onDimInput(e: WechatMiniprogram.Input) {
    const key = e.currentTarget.dataset.key as string;
    const dims = this.data.dims.map((d: Dim) => (d.key === key ? { ...d, value: e.detail.value } : d));
    this.setData({ dims });
  },

  // 设计稿「长方体选择罐号19」：点"生成罐表"后弹出罐号选择（居中偏上，不贴任何一行）。
  // 顶边在设计稿里是 y=595（面板自带 -4 的上移，所以这里写 599），页面区从 181 开始，按屏宽换算成 px。
  onGenerate() {
    const info = wx.getWindowInfo();
    this.setData({ pickerShow: true, pickerTop: ((599 - 181) * info.windowWidth) / 750 });
  },
  onPickTank(e: WechatMiniprogram.CustomEvent<{ index: number }>) {
    this.setData({ tankIndex: e.detail.index, pickerShow: false });
    showComingSoon(); // 协议里没有生成/下发罐表的方法
  },
  onClosePicker() {
    this.setData({ pickerShow: false });
  },

  // 真实的本机保存：只存这个形状的尺寸，不会下发给设备
  onSaveLocal() {
    const saved: Record<string, string> = {};
    this.data.dims.forEach((d: Dim) => (saved[d.key] = d.value));
    try {
      wx.setStorageSync(storageKey(this.data.shape), saved);
      wx.showToast({ title: '已保存到本机，不会下发给设备', icon: 'none' });
    } catch {
      wx.showToast({ title: '保存失败', icon: 'none' });
    }
  },

  onSaveDevice() {
    showComingSoon(); // 协议里没有下发罐表的方法
  },
});

export {};
