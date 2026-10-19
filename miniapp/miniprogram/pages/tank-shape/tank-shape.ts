import { setNavTitle, t } from '../../utils/i18n';

const SHAPE_KEYS = ['horizontal', 'vertical', 'cuboid'];

Page({
  data: { shapes: SHAPE_KEYS.map((key) => ({ key, label: '' })) },

  onShow() {
    setNavTitle('tankShape.navTitle');
    this.setData({ shapes: SHAPE_KEYS.map((key) => ({ key, label: t(`tankShape.${key}`) })) });
  },

  onShapeTap(e: WechatMiniprogram.BaseEvent) {
    const key = e.currentTarget.dataset.key as string;
    wx.navigateTo({ url: `/pages/tank-shape-edit/tank-shape-edit?shape=${key}` });
  },
});

export {};
