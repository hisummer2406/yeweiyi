import { showComingSoon } from '../../utils/comingSoon';
type Entry = {
  tankIndex: number;
  date: string;
  beforeLevel: string;
  volume: string;
};

const emptyEntry = (): Entry => ({ tankIndex: -1, date: '', beforeLevel: '', volume: '' });

Page({
  data: {
    // 设计稿的罐号选择是 1–12 号
    tankOptions: Array.from({ length: 12 }, (_, i) => `${i + 1}号`),
    inbound: emptyEntry(),
    outbound: emptyEntry(),
    pickerShow: false,
    pickerTop: 0,
    pickerValue: -1,
    pickerKind: 'inbound' as 'inbound' | 'outbound',
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
    const kind = e.currentTarget.dataset.kind as string;
    const entry = kind === '入库' ? this.data.inbound : this.data.outbound;
    wx.showModal({
      title: `${kind}记录预览`,
      content: `罐号 ${this.data.tankOptions[entry.tankIndex] || '未选择'}\n时间 ${entry.date || '未选择'}\n前高度 ${entry.beforeLevel || '0'}mm\n${kind}升数 ${entry.volume || '0'}L`,
      showCancel: false,
    });
  },
});

export {};
