import { showComingSoon } from '../../utils/comingSoon';
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
  { key: 'stock', label: '库存记录', generate: true, deleteOn: false, intervalMin: '' },
];

Page({
  data: {
    groups: GROUPS,
    dialogShow: false,
    dialogKey: '',
  },

  onToggle(e: WechatMiniprogram.BaseEvent) {
    const { key, field } = e.currentTarget.dataset as { key: string; field: 'generate' | 'deleteOn' };
    const groups = this.data.groups.map((g: RecordGroup) =>
      g.key === key ? { ...g, [field]: !g[field] } : g,
    );
    this.setData({ groups });
    showComingSoon(); // 云端没有对应接口
  },

  onIntervalInput(e: WechatMiniprogram.Input) {
    const groups = this.data.groups.map((g: RecordGroup) =>
      g.key === 'stock' ? { ...g, intervalMin: e.detail.value } : g,
    );
    this.setData({ groups });
  },

  onClearTap(e: WechatMiniprogram.BaseEvent) {
    this.setData({ dialogShow: true, dialogKey: e.currentTarget.dataset.key as string });
  },

  onDialogCancel() {
    this.setData({ dialogShow: false });
  },

  // 确定后也不会真的清空：云端没有这个接口，在这里说清楚，而不是假装成功
  onDialogOk() {
    const group = this.data.groups.find((g: RecordGroup) => g.key === this.data.dialogKey);
    this.setData({ dialogShow: false });
    showComingSoon(); // 云端没有清空接口
  },

  noop() {},
});

export {};
