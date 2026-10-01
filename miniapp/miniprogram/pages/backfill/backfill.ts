type Entry = {
  tankIndex: number;
  date: string;
  beforeLevel: string;
  volume: string;
};

const emptyEntry = (): Entry => ({ tankIndex: 0, date: '', beforeLevel: '0', volume: '0' });

Page({
  data: {
    tankOptions: ['1号罐', '2号罐', '3号罐', '4号罐'],
    inbound: emptyEntry(),
    outbound: emptyEntry(),
  },

  onInboundTankChange(e: WechatMiniprogram.PickerChange) {
    this.setData({ 'inbound.tankIndex': Number(e.detail.value) });
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

  onOutboundTankChange(e: WechatMiniprogram.PickerChange) {
    this.setData({ 'outbound.tankIndex': Number(e.detail.value) });
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
    const kind = e.currentTarget.dataset.kind as string;
    wx.showToast({ title: `云端没有${kind}补录接口，暂不能保存`, icon: 'none', duration: 3000 });
  },

  onPreview(e: WechatMiniprogram.BaseEvent) {
    const kind = e.currentTarget.dataset.kind as string;
    const entry = kind === '入库' ? this.data.inbound : this.data.outbound;
    wx.showModal({
      title: `${kind}记录预览`,
      content: `罐号 ${this.data.tankOptions[entry.tankIndex]}\n时间 ${entry.date || '未选择'}\n前高度 ${entry.beforeLevel}mm\n${kind}升数 ${entry.volume}L`,
      showCancel: false,
    });
  },
});

export {};
