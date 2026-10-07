const SHAPES = [
  { key: 'horizontal', label: '卧罐' },
  { key: 'vertical', label: '立罐' },
  { key: 'cuboid', label: '长方体' },
];

Page({
  data: { shapes: SHAPES },

  onShapeTap(e: WechatMiniprogram.BaseEvent) {
    const key = e.currentTarget.dataset.key as string;
    wx.navigateTo({ url: `/pages/tank-shape-edit/tank-shape-edit?shape=${key}` });
  },
});

export {};
