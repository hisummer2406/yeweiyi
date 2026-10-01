type ExportItem = { key: string; label: string; on: boolean };

const ITEMS: ExportItem[] = [
  { key: 'alarm', label: '报警记录导出', on: true },
  { key: 'outbound', label: '出库记录导出', on: false },
  { key: 'inbound', label: '入库记录导出', on: false },
  { key: 'shift', label: '交班记录导出', on: false },
  { key: 'stock', label: '库存记录导出', on: false },
];

Page({
  data: {
    items: ITEMS,
  },

  onSwitchChange(e: WechatMiniprogram.SwitchChange) {
    const key = e.currentTarget.dataset.key as string;
    const items = this.data.items.map((i: ExportItem) =>
      i.key === key ? { ...i, on: e.detail.value } : i,
    );
    this.setData({ items });
    wx.showToast({ title: '云端没有导出接口，仅展示交互', icon: 'none' });
  },
});

export {};
