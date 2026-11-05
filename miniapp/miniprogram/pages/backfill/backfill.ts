import { showComingSoon } from '../../utils/comingSoon';
import { setNavTitle, t, texts } from '../../utils/i18n';

type Entry = {
  tankIndex: number;
  date: string;
  beforeLevel: string;
  volume: string;
};

const emptyEntry = (): Entry => ({ tankIndex: -1, date: '', beforeLevel: '', volume: '' });

Page({
  data: {
    text: texts('backfill'),
    form: texts('form'),
    // 设计稿的罐号选择是 1–12 号
    tankOptions: [] as string[],
    inbound: emptyEntry(),
    outbound: emptyEntry(),
    pickerShow: false,
    pickerTop: 0,
    pickerValue: -1,
    pickerKind: 'inbound' as 'inbound' | 'outbound',
  },

  onShow() {
    setNavTitle('backfill.navTitle');
    this.setData({
      text: texts('backfill'),
      form: texts('form'),
      tankOptions: Array.from({ length: 12 }, (_, i) => t('form.numbered', { n: i + 1 })),
    });
  },

  // 入库、出库两段共用一个罐号面板：记下是哪一段点的，选完写回那一段
  onOpenTank(e: WechatMiniprogram.BaseEvent) {
    const kind = e.currentTarget.dataset.kind as 'inbound' | 'outbound';
    wx.createSelectorQuery()
      .select(`#row-${kind}-tank`)
      .boundingClientRect((rect) => {
        if (rect) {
          this.setData({
            pickerShow: true,
            pickerTop: rect.bottom,
            pickerKind: kind,
            pickerValue: this.data[kind].tankIndex,
          });
        }
      })
      .exec();
  },
  onPickTank(e: WechatMiniprogram.CustomEvent<{ index: number }>) {
    this.setData({ [`${this.data.pickerKind}.tankIndex`]: e.detail.index, pickerShow: false });
  },
  onClosePicker() {
    this.setData({ pickerShow: false });
  },
  onInboundDateChange(e: WechatMiniprogram.PickerChange) {
    this.setData({ 'inbound.date': e.detail.value });
  },
  onInboundLevelInput(e: WechatMiniprogram.Input) {
    this.setData({ 'inbound.beforeLevel': e.detail.value });
  },
  onInboundVolumeInput(e: WechatMiniprogram.Input) {
    this.setData({ 'inbound.volume': e.detail.value });
  },

  onOutboundDateChange(e: WechatMiniprogram.PickerChange) {
    this.setData({ 'outbound.date': e.detail.value });
  },
  onOutboundLevelInput(e: WechatMiniprogram.Input) {
    this.setData({ 'outbound.beforeLevel': e.detail.value });
  },
  onOutboundVolumeInput(e: WechatMiniprogram.Input) {
    this.setData({ 'outbound.volume': e.detail.value });
  },

  onSave(e: WechatMiniprogram.BaseEvent) {
    showComingSoon(); // 云端没有补录接口（yeweiyi-cloud#69）
  },

  onPreview(e: WechatMiniprogram.BaseEvent) {
    const kind = e.currentTarget.dataset.kind as 'inbound' | 'outbound';
    const entry = kind === 'inbound' ? this.data.inbound : this.data.outbound;
    const notSelected = t('backfill.notSelected');
    wx.showModal({
      title: t(kind === 'inbound' ? 'backfill.previewTitleIn' : 'backfill.previewTitleOut'),
      content: t('backfill.previewBody', {
        tank: this.data.tankOptions[entry.tankIndex] || notSelected,
        time: entry.date || notSelected,
        level: entry.beforeLevel || '0',
        volume: entry.volume || '0',
      }),
      showCancel: false,
    });
  },
});

export {};
