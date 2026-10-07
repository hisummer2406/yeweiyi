type Threshold = {
  key: string;
  label: string;
  unit: string;
  value: string;
};

// 初值留空：输入框用占位 "0"，与设计稿的灰色 0 一致
const DEFAULT_THRESHOLDS: Threshold[] = [
  { key: 'highHigh', label: '高高液位报警', unit: '毫米', value: '' },
  { key: 'high', label: '高液位报警', unit: '毫米', value: '' },
  { key: 'lowLow', label: '低低液位报警', unit: '毫米', value: '' },
  { key: 'low', label: '低液位报警', unit: '毫米', value: '' },
  { key: 'waterHigh', label: '高水位报警', unit: '毫米', value: '' },
  { key: 'waterLow', label: '低水位报警', unit: '毫米', value: '' },
  { key: 'tempHigh', label: '高温报警', unit: '℃', value: '' },
  { key: 'tempLow', label: '低温报警', unit: '℃', value: '' },
];

type PickKind = 'tank' | 'sensor' | 'type' | 'color';

const numbered = (n: number) => Array.from({ length: n }, (_, i) => `${i + 1}号`);

Page({
  data: {
    // 设计稿的罐号、传感器 ID 都是 1–12 号的面板
    tankOptions: numbered(12),
    tankIndex: -1,
    sensorOptions: numbered(12),
    sensorIndex: -1,
    // 设备端 OilCanSet.gunType 只有卧罐/立罐两种（0 卧 / 1 立），设计稿的下拉也是这两项
    typeOptions: ['立罐', '卧罐'],
    typeIndex: -1,
    // 设计稿「物料颜色」的 12 色色板，行优先
    colorOptions: [
      '#05507c', '#7c40aa', '#1d7cd8', '#e12c71', '#05abda', '#fe372f',
      '#0bb179', '#ff5b26', '#54c64d', '#fd8536', '#dff453', '#fde344',
    ],
    colorIndex: -1,
    density: '',
    height: '',
    capacity: '',
    thresholds: DEFAULT_THRESHOLDS,
    pickerKind: 'tank' as PickKind,
    pickerTop: 0,
    gridShow: false,
    sideShow: false,
  },

  // 点选择行：量出该行 bottom，把对应的面板贴在它下面
  onOpenPicker(e: WechatMiniprogram.BaseEvent) {
    const kind = e.currentTarget.dataset.pick as PickKind;
    wx.createSelectorQuery()
      .select(`#row-${kind}`)
      .boundingClientRect((rect) => {
        if (!rect) return;
        const grid = kind === 'tank' || kind === 'sensor';
        this.setData({ pickerKind: kind, pickerTop: rect.bottom, gridShow: grid, sideShow: !grid });
      })
      .exec();
  },
  onPickGrid(e: WechatMiniprogram.CustomEvent<{ index: number }>) {
    const key = this.data.pickerKind === 'sensor' ? 'sensorIndex' : 'tankIndex';
    this.setData({ [key]: e.detail.index, gridShow: false });
  },
  onPickSide(e: WechatMiniprogram.CustomEvent<{ index: number }>) {
    const key = this.data.pickerKind === 'color' ? 'colorIndex' : 'typeIndex';
    this.setData({ [key]: e.detail.index, sideShow: false });
  },
  onClosePicker() {
    this.setData({ gridShow: false, sideShow: false });
  },

  onDensityInput(e: WechatMiniprogram.Input) {
    this.setData({ density: e.detail.value });
  },
  onHeightInput(e: WechatMiniprogram.Input) {
    this.setData({ height: e.detail.value });
  },
  onCapacityInput(e: WechatMiniprogram.Input) {
    this.setData({ capacity: e.detail.value });
  },
  onThresholdInput(e: WechatMiniprogram.Input) {
    const key = e.currentTarget.dataset.key as string;
    const thresholds = this.data.thresholds.map((t: Threshold) =>
      t.key === key ? { ...t, value: e.detail.value } : t,
    );
    this.setData({ thresholds });
  },

  onSave() {
    wx.showToast({
      title: 'config.setTank / config.setAlarmThresholds 参数结构未定义，暂不能真的下发',
      icon: 'none',
      duration: 3000,
    });
  },

  onReset() {
    this.setData({
      density: '',
      height: '',
      capacity: '',
      thresholds: DEFAULT_THRESHOLDS,
    });
  },
});

export {};
