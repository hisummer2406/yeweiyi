import { showComingSoon } from '../../utils/comingSoon';
import { setNavTitle, t, texts } from '../../utils/i18n';

// `label` is filled in the current language when the page is shown.
type ExportItem = { key: string; on: boolean; label: string };

const ITEMS: ExportItem[] = [
  { key: 'alarm', on: true, label: '' },
  { key: 'outbound', on: false, label: '' },
  { key: 'inbound', on: false, label: '' },
  { key: 'shift', on: false, label: '' },
  { key: 'stock', on: false, label: '' },
];

Page({
  data: {
    items: ITEMS,
  },

  onShow() {
    setNavTitle('repSet.navTitle');
    this.setData({
      items: this.data.items.map((i: ExportItem) => ({ ...i, label: t(`repSet.export_${i.key}`) })),
    });
  },

  onToggle(e: WechatMiniprogram.BaseEvent) {
    const key = e.currentTarget.dataset.key as string;
    const items = this.data.items.map((i: ExportItem) =>
      i.key === key ? { ...i, on: !i.on } : i,
    );
    this.setData({ items });
    showComingSoon(); // 云端没有导出接口
  },
});

export {};
