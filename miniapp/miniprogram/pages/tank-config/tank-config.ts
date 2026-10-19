import { showComingSoon } from '../../utils/comingSoon';
import { setNavTitle, t, texts } from '../../utils/i18n';

type Threshold = {
  key: string;
  unitKey: 'mm' | 'degC';
  value: string;
};

// 初值留空：输入框用占位 "0"，与设计稿的灰色 0 一致
const DEFAULT_THRESHOLDS: Threshold[] = [
  { key: 'highHigh', unitKey: 'mm', value: '' },
  { key: 'high', unitKey: 'mm', value: '' },
  { key: 'lowLow', unitKey: 'mm', value: '' },
  { key: 'low', unitKey: 'mm', value: '' },
  { key: 'waterHigh', unitKey: 'mm', value: '' },
  { key: 'waterLow', unitKey: 'mm', value: '' },
  { key: 'tempHigh', unitKey: 'degC', value: '' },
  { key: 'tempLow', unitKey: 'degC', value: '' },
];

type PickKind = 'tank' | 'sensor' | 'type' | 'color';

const numbered = (n: number) => Array.from({ length: n }, (_, i) => t('form.numbered', { n: i + 1 }));

Page({
  data: {
    text: texts('tankCfg'),
    form: texts('form'),
    // 设计稿的罐号、传感器 ID 都是 1–12 号的面板
    tankOptions: [] as string[],
    tankIndex: -1,
    sensorOptions: [] as string[],
    sensorIndex: -1,
    // 设备端 OilCanSet.gunType 只有卧罐/立罐两种（0 卧 / 1 立），设计稿的下拉也是这两项
    typeOptions: [] as string[],
    typeIndex: -1,
    // 设计稿「物料颜色」的 12 色色板，行优先
    colorOptions: [
      '#05507c', '#7c40aa', '#1d7cd8', '#e12c71', '#05abda', '#fe372f',
      '#0bb179', '#ff5b26', '#54c64d', '#fd8536', '#dff453', '#fde344',
    ],
    colorIndex: -1,
    // 手动输入的自由文本（汽油、柴油、大豆油…），不是枚举：云端没有介质类型的字段，参数结构定下来之前只在本页
    mediumType: '',
    density: '',
    height: '',
    capacity: '',
    thresholds: DEFAULT_THRESHOLDS,
    pickerKind: 'tank' as PickKind,
    pickerTop: 0,
    gridShow: false,
    sideShow: false,
  },

  onShow() {
    setNavTitle('tankCfg.navTitle');
    this.setData({
      text: texts('tankCfg'),
      form: texts('form'),
      tankOptions: numbered(12),
      sensorOptions: numbered(12),
      typeOptions: [t('tankCfg.typeVertical'), t('tankCfg.typeHorizontal')],
    });
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

  onMediumTypeInput(e: WechatMiniprogram.Input) {
    this.setData({ mediumType: e.detail.value });
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
    showComingSoon(); // config.setTank / config.setAlarmThresholds 参数结构未定义
  },

  onReset() {
    this.setData({
      mediumType: '',
      density: '',
      height: '',
      capacity: '',
      thresholds: DEFAULT_THRESHOLDS,
    });
  },
});

export {};
