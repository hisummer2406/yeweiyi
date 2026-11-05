// 设计稿「参数修正-罐号选择08」「罐表设置-长方体选择罐号19」「储罐设置-下拉框罐号11」：
// 点选择行后在行下方弹出的 3 列药丸面板，不是系统 picker。页面用 wx.createSelectorQuery 量出被点行的
// bottom 传给 `top`（px，相对页面可视区），这样页面滚动之后面板仍然贴着被点的那一行。
Component({
  properties: {
    show: { type: Boolean, value: false },
    options: { type: Array, value: [] as string[] },
    // 当前选中的下标；-1 表示还没选
    value: { type: Number, value: -1 },
    // 有标题时面板顶部多一条浅色标题栏（「罐号选择」）
    title: { type: String, value: '' },
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
