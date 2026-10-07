// 设计稿「储罐设置-下拉框储罐类型13」「储罐设置-下拉框物料颜色14」：贴在被点行下方、靠右的小面板。
// kind="pills"：一列药丸（储罐类型：立罐/卧罐）；kind="swatches"：两列色块（物料颜色）。
// 与 grid-picker 一样，top 由页面用 wx.createSelectorQuery 量出被点行的 bottom 传入（px）。
Component({
  properties: {
    show: { type: Boolean, value: false },
    kind: { type: String, value: 'pills' },
    // pills 用文字；swatches 用颜色值（#rrggbb）
    options: { type: Array, value: [] as string[] },
    value: { type: Number, value: -1 },
    top: { type: Number, value: 0 },
  },
  methods: {
    onPick(e: WechatMiniprogram.BaseEvent) {
      this.triggerEvent('pick', { index: Number(e.currentTarget.dataset.index) });
    },
    onClose() {
      this.triggerEvent('close');
    },
    noop() {},
  },
});
