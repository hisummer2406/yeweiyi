// 罐表设置：按形状和尺寸生成液位 → 容积换算表（utils/tankTable.ts，与设备端 EllipseTable 逐点一致）。
// 字母含义与设备中文界面一致：卧罐 H 直径、L 筒体长度、R 单侧封头高度；立罐 H 罐高、R（设备按直径计算）；
// 长方体 H 罐高、L 长、R 宽。
// 「保存到控制器」需要新的下发方法（设备、云端、小程序三端），下一步单独实现；在此之前只提示暂未开放。
import { showComingSoon } from '../../utils/comingSoon';
import { t, texts } from '../../utils/i18n';
import { DIM_KEYS, generateTankTable, parseDims, previewRows, type TankShape, type TankTable } from '../../utils/tankTable';

type Dim = { key: string; value: string };

// 尺寸草稿（不按账号区分，登出时由 utils/storage.ts clearLocalDrafts 清掉）。
const storageKey = (shape: string) => `tankshape-draft-${shape}`;

Page({
  data: {
    text: texts('tankEdit'),
    form: texts('form'),
    shape: 'horizontal' as TankShape,
    dims: [] as Dim[],
    // 生成结果：'' = 尚未生成或尺寸已改动（旧表作废）
    summary: '',
    rows: [] as { heightMm: number; volumeL: number }[],
    sampled: false,
  },

  table: null as TankTable | null,

  onLoad(query: Record<string, string>) {
    const shape = (query.shape in DIM_KEYS ? query.shape : 'horizontal') as TankShape;
    wx.setNavigationBarTitle({ title: t(`tankShape.${shape}`) });
    let saved: Record<string, string> = {};
    try {
      saved = wx.getStorageSync(storageKey(shape)) || {};
    } catch {
      // 读不到就当没有保存过
    }
    this.setData({
      shape,
      dims: DIM_KEYS[shape].map((key) => ({ key, value: saved[key] || '' })),
    });
  },

  onDimInput(e: WechatMiniprogram.Input) {
    const key = e.currentTarget.dataset.key as string;
    const dims = this.data.dims.map((d: Dim) => (d.key === key ? { ...d, value: e.detail.value } : d));
    // 尺寸变了，之前生成的表不再对应当前输入
    this.table = null;
    this.setData({ dims, summary: '', rows: [], sampled: false });
    const saved: Record<string, string> = {};
    dims.forEach((d: Dim) => (saved[d.key] = d.value));
    try {
      wx.setStorageSync(storageKey(this.data.shape), saved);
    } catch {
      // 草稿只是方便，写不进去不影响生成
    }
  },

  onGenerate() {
    const values: Record<string, string> = {};
    this.data.dims.forEach((d: Dim) => (values[d.key] = d.value));
    const parsed = parseDims(this.data.shape, values);
    if (!parsed.ok) {
      wx.showToast({ title: t(parsed.key, parsed.params), icon: 'none', duration: 3000 });
      return;
    }
    const table = generateTankTable(this.data.shape, parsed.mm);
    const n = table.heightMm.length;
    if (n === 0) return;
    this.table = table;
    const rows = previewRows(table);
    this.setData({
      summary: t('tankEdit.summary', {
        n: String(n),
        height: String(table.heightMm[n - 1]),
        volume: String(table.volumeL[n - 1]),
      }),
      rows,
      sampled: rows.length < n,
    });
    wx.showToast({ title: t('tankEdit.generated'), icon: 'none' });
  },

  onSaveDevice() {
    if (!this.table) {
      wx.showToast({ title: t('tankEdit.generateFirst'), icon: 'none' });
      return;
    }
    showComingSoon(); // 下发罐表的协议方法尚未实现（第二步）
  },
});

export {};
