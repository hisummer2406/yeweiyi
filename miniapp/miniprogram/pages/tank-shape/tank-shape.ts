type Dim = { key: string; label: string; value: string };

const DIMS_BY_SHAPE: Record<string, Dim[]> = {
  horizontal: [
    { key: 'H', label: 'H（罐体直径）', value: '0' },
    { key: 'L', label: 'L（罐体长度）', value: '0' },
    { key: 'R', label: 'R（封头半径）', value: '0' },
  ],
  vertical: [
    { key: 'H', label: 'H（罐体高度）', value: '0' },
    { key: 'R', label: 'R（罐体半径）', value: '0' },
  ],
  cuboid: [
    { key: 'H', label: 'H（高）', value: '0' },
    { key: 'L', label: 'L（长）', value: '0' },
    { key: 'W', label: 'W（宽）', value: '0' },
  ],
};

// icon 直接取自设计稿「罐表设置15」画板的形状选择图，不是文字符号
const SHAPES = [
  { key: 'horizontal', icon: 'shape-horizontal', label: '卧罐' },
  { key: 'vertical', icon: 'shape-vertical', label: '立罐' },
  { key: 'cuboid', icon: 'shape-cuboid', label: '长方体' },
];

Page({
  data: {
    tankOptions: ['1号罐', '2号罐', '3号罐', '4号罐'],
    tankIndex: 0,
    shapes: SHAPES,
    activeShape: 'horizontal',
    dims: DIMS_BY_SHAPE.horizontal,
    previewRows: [] as { level: number; volume: number }[],
  },

  onTankChange(e: WechatMiniprogram.PickerChange) {
    this.setData({ tankIndex: Number(e.detail.value) });
  },

  onShapeTap(e: WechatMiniprogram.BaseEvent) {
    const key = e.currentTarget.dataset.key as string;
    this.setData({ activeShape: key, dims: DIMS_BY_SHAPE[key], previewRows: [] });
  },

  onDimInput(e: WechatMiniprogram.Input) {
    const key = e.currentTarget.dataset.key as string;
    const dims = this.data.dims.map((d: Dim) => (d.key === key ? { ...d, value: e.detail.value } : d));
    this.setData({ dims });
  },

  onGenerate() {
    // 本地演示：按线性插值造 5 行假数据，不代表真实的液位-容积换算公式
    const rows = [0, 25, 50, 75, 100].map((pct) => ({
      level: pct * 10,
      volume: Math.round(pct * 12.34),
    }));
    this.setData({ previewRows: rows });
    wx.showToast({ title: '仅为本地演示数据，真实换算公式需产品/工程确认', icon: 'none', duration: 3000 });
  },

  onSaveLocal() {
    wx.showToast({ title: '演示：已保存到本机', icon: 'none' });
  },

  onSaveDevice() {
    wx.showToast({ title: '协议里没有下发罐表的方法，暂不支持', icon: 'none', duration: 3000 });
  },
});

export {};
