type Threshold = {
  key: string;
  label: string;
  unit: string;
  value: string;
};

const DEFAULT_THRESHOLDS: Threshold[] = [
  { key: 'highHigh', label: '高高液位报警', unit: '毫米', value: '0' },
  { key: 'high', label: '高液位报警', unit: '毫米', value: '0' },
  { key: 'lowLow', label: '低低液位报警', unit: '毫米', value: '0' },
  { key: 'low', label: '低液位报警', unit: '毫米', value: '0' },
  { key: 'waterHigh', label: '高水位报警', unit: '毫米', value: '0' },
  { key: 'waterLow', label: '低水位报警', unit: '毫米', value: '0' },
  { key: 'tempHigh', label: '高温报警', unit: '℃', value: '0' },
  { key: 'tempLow', label: '低温报警', unit: '℃', value: '0' },
];

Page({
  data: {
    tankOptions: ['1号罐', '2号罐', '3号罐', '4号罐'],
    tankIndex: 0,
    sensorOptions: ['SENSOR-A1', 'SENSOR-A2', 'SENSOR-B1'],
    sensorIndex: 0,
    shapeOptions: ['卧罐', '立罐', '长方体'],
    shapeIndex: 0,
    colorOptions: ['无色', '黄色', '红色', '蓝色'],
    colorIndex: 0,
    density: '0',
    height: '0',
    capacity: '0',
    thresholds: DEFAULT_THRESHOLDS,
  },

  onTankChange(e: WechatMiniprogram.PickerChange) {
    this.setData({ tankIndex: Number(e.detail.value) });
  },
  onSensorChange(e: WechatMiniprogram.PickerChange) {
    this.setData({ sensorIndex: Number(e.detail.value) });
  },
  onShapeChange(e: WechatMiniprogram.PickerChange) {
    this.setData({ shapeIndex: Number(e.detail.value) });
  },
  onColorChange(e: WechatMiniprogram.PickerChange) {
    this.setData({ colorIndex: Number(e.detail.value) });
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
      density: '0',
      height: '0',
      capacity: '0',
      thresholds: DEFAULT_THRESHOLDS,
    });
  },
});

export {};
