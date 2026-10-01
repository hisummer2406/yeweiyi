type RecordGroup = {
  key: string;
  label: string;
  generate: boolean;
  deleteOn: boolean;
  intervalMin?: string;
};

const GROUPS: RecordGroup[] = [
  { key: 'alarm', label: '报警记录', generate: true, deleteOn: false },
  { key: 'inbound', label: '入库记录', generate: true, deleteOn: false },
  { key: 'outbound', label: '出库记录', generate: true, deleteOn: false },
  { key: 'shift', label: '交班记录', generate: true, deleteOn: false },
  { key: 'stock', label: '库存记录', generate: true, deleteOn: false, intervalMin: '0' },
];

Page({
  data: {
    groups: GROUPS,
  },

  onSwitchChange(e: WechatMiniprogram.SwitchChange) {
    const { key, field } = e.currentTarget.dataset as { key: string; field: 'generate' | 'deleteOn' };
    const groups = this.data.groups.map((g: RecordGroup) =>
      g.key === key ? { ...g, [field]: e.detail.value } : g,
    );
    this.setData({ groups });
    wx.showToast({ title: '云端没有对应接口，仅展示交互', icon: 'none' });
  },

  onIntervalInput(e: WechatMiniprogram.Input) {
    const groups = this.data.groups.map((g: RecordGroup) =>
      g.key === 'stock' ? { ...g, intervalMin: e.detail.value } : g,
    );
    this.setData({ groups });
  },

  onClearTap(e: WechatMiniprogram.BaseEvent) {
    const key = e.currentTarget.dataset.key as string;
    const group = this.data.groups.find((g: RecordGroup) => g.key === key);
    wx.showModal({
      title: '清空记录',
      content: '谨慎！是否清空记录，记录无法恢复',
      confirmText: '确定',
      success: (res) => {
        if (res.confirm) {
          wx.showToast({ title: `云端没有清空${group?.label || ''}的接口`, icon: 'none', duration: 3000 });
        }
      },
    });
  },
});

export {};
