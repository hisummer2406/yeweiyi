import { showComingSoon } from '../../utils/comingSoon';
import { setNavTitle, t, texts } from '../../utils/i18n';

type RecordGroup = {
  key: string;
  generate: boolean;
  deleteOn: boolean;
  intervalMin?: string;
};

const GROUPS: RecordGroup[] = [
  { key: 'alarm', generate: true, deleteOn: false },
  { key: 'inbound', generate: true, deleteOn: false },
  { key: 'outbound', generate: true, deleteOn: false },
  { key: 'shift', generate: true, deleteOn: false },
  { key: 'stock', generate: true, deleteOn: false, intervalMin: '' },
];

// "Generate / Delete / Clear <records>" per group, in the current language.
function groupLabels(): Record<string, { generate: string; del: string; clear: string }> {
  const out: Record<string, { generate: string; del: string; clear: string }> = {};
  for (const g of GROUPS) {
    const name = t(`recSet.name_${g.key}`);
    out[g.key] = {
      generate: t('recSet.generate', { name }),
      del: t('recSet.delete', { name }),
      clear: t('recSet.clear', { name }),
    };
  }
  return out;
}

Page({
  data: {
    text: texts('recSet'),
    form: texts('form'),
    labels: {} as Record<string, { generate: string; del: string; clear: string }>,
    groups: GROUPS,
    dialogShow: false,
    dialogKey: '',
  },

  onShow() {
    setNavTitle('recSet.navTitle');
    this.setData({ text: texts('recSet'), form: texts('form'), labels: groupLabels() });
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
