// 罐表设置：按形状和尺寸生成液位 → 容积换算表（utils/tankTable.ts，与设备端 EllipseTable 逐点一致）。
// 字母含义与设备中文界面一致：卧罐 H 直径、L 筒体长度、R 单侧封头高度；立罐 H 罐高、R（设备按直径计算）；
// 长方体 H 罐高、L 长、R 宽。
// 「保存到控制器」（#49）：只下发罐型和尺寸（config.setTankTable），设备用同一公式自行生成并写入所选罐；
// 对象是首页当前油站的设备，罐号从该设备可见的罐里选；提交保护与参数修正相同（utils/configSubmit.ts），
// 受理后进入操作记录跟踪结果与标定回显。仅管理员。
import { roleFromToken } from '../../utils/auth';
import { confirmAndSend } from '../../utils/configSubmit';
import { describeError } from '../../utils/errors';
import { t, texts } from '../../utils/i18n';
import { get } from '../../utils/request';
import { isReadOnlyRole } from '../../utils/roles';
import { FOLLOW_NOTICES } from '../../utils/stationDevice';
import { loadRecordsContext } from '../../utils/stationPref';
import { getToken } from '../../utils/storage';
import { DIM_KEYS, generateTankTable, parseDims, previewRows, tableArgs, type TankShape, type TankTable } from '../../utils/tankTable';
import type { DeviceTanksResponse } from '../../types/api';

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
    reason: '',
    // 罐号选择（下发时）：当前设备可见的罐
    tankOptions: [] as string[],
    pickerShow: false,
    pickerTop: 0,
  },

  table: null as TankTable | null,
  mm: null as Record<string, number> | null,
  target: null as { device: string; stationId: number | null; tanks: number[] } | null,

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
    this.mm = null;
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
    this.mm = parsed.mm;
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

  onReasonInput(e: WechatMiniprogram.Input) {
    this.setData({ reason: e.detail.value });
  },

  // 先检查本机条件，再读当前油站的设备和可见罐，弹出罐号选择；选定后确认并下发。
  onSaveDevice() {
    if (!this.table || !this.mm) {
      wx.showToast({ title: t('tankEdit.generateFirst'), icon: 'none' });
      return;
    }
    if (isReadOnlyRole(roleFromToken(getToken()))) {
      wx.showToast({ title: t('tankEdit.adminOnly'), icon: 'none' });
      return;
    }
    if (!(this.data.reason as string).trim()) {
      wx.showToast({ title: t('tankEdit.reasonRequired'), icon: 'none' });
      return;
    }
    wx.showLoading({ title: t('tankEdit.loadingTarget'), mask: true });
    loadRecordsContext()
      .then((ctx) => {
        if (ctx.kind !== 'ok') {
          const key = ctx.kind === 'no-station' ? 'tankEdit.noStation' : ctx.kind === 'ambiguous' ? FOLLOW_NOTICES.ambiguous : 'records.emptyStation';
          throw new Error(t(key));
        }
        const device = ctx.device.device_name;
        const stationId = typeof ctx.device.station_id === 'number' ? ctx.device.station_id : null;
        return get<DeviceTanksResponse>(`/api/devices/${encodeURIComponent(device)}/tanks`).then((res) => {
          const tanks = Array.from(new Set((res.tanks || []).map((x) => x.index))).sort((a, b) => a - b);
          if (tanks.length === 0) throw new Error(t('tankEdit.noTanks'));
          this.target = { device, stationId, tanks };
          const info = wx.getWindowInfo();
          // 设计稿「长方体选择罐号19」：面板顶边 y=599（页面区从 181 开始），按屏宽换算成 px。
          this.setData({
            tankOptions: tanks.map((n) => t('tank.no', { n })),
            pickerShow: true,
            pickerTop: ((599 - 181) * info.windowWidth) / 750,
          });
        });
      })
      .catch((err: Error) => {
        wx.showModal({ title: t('cfgSend.failTitle'), content: err.message || describeError(err), showCancel: false });
      })
      .finally(() => wx.hideLoading());
  },

  onPickTank(e: WechatMiniprogram.CustomEvent<{ index: number }>) {
    this.setData({ pickerShow: false });
    const target = this.target;
    const tank = target?.tanks[e.detail.index];
    if (!target || tank === undefined || !this.table || !this.mm) return;
    const n = this.table.heightMm.length;
    const dimsText = this.data.dims.map((d: Dim) => `${d.key} ${d.value}`).join(' · ');
    void confirmAndSend({
      device: target.device,
      stationId: target.stationId,
      tank,
      method: 'config.setTankTable',
      args: tableArgs(this.data.shape, this.mm),
      reason: (this.data.reason as string).trim(),
      lines: [
        { label: t('tankEdit.lineShape'), before: t('tankEdit.lineBefore'), after: t(`tankShape.${this.data.shape}`) },
        { label: t('tankEdit.lineDims'), before: '—', after: `${dimsText} mm` },
        {
          label: t('tankEdit.lineTable'),
          before: '—',
          after: t('tankEdit.summary', { n: String(n), height: String(this.table.heightMm[n - 1]), volume: String(this.table.volumeL[n - 1]) }),
        },
      ],
    });
  },

  onClosePicker() {
    this.setData({ pickerShow: false });
  },
});

export {};
