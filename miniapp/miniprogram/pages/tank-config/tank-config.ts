import { describeError } from '../../utils/errors';
import { currentLocale, setNavTitle, t, texts } from '../../utils/i18n';
import { get } from '../../utils/request';
import { formatLocalTime } from '../../utils/format';
import { userKeyFromToken } from '../../utils/auth';
import { getToken } from '../../utils/storage';
import { loadRecordsContext } from '../../utils/stationPref';
import { FOLLOW_NOTICES } from '../../utils/stationDevice';
import {
  DRAFT_FIELDS,
  draftKey,
  emptyDraft,
  readDraft,
  sameDraft,
  validateDraft,
  type TankDraft,
} from '../../utils/tankDraft';
import type { DeviceTanksResponse } from '../../types/api';

// 储罐设置（miniapp#18）：按设计稿排的表单，只保存为本手机上的草稿。
//
// The object being edited is shown on top — the home page's current station, its one device, and one of
// the tanks this user can see on it (not a fixed 1–12 list). 保存 writes a draft keyed by account + device
// + tank (utils/tankDraft.ts); it is not sent anywhere, and the controller's real settings are neither read
// nor changed: that is cloud#70 (read) / #71 (write). The only requests here are the ones that decide the
// station, the device and its tanks.

type State = 'loading' | 'ok' | 'no-station' | 'blocked' | 'no-tanks' | 'error';

type FieldView = { key: string; label: string; unit: string; value: string; signed: boolean };

function fieldViews(draft: TankDraft): FieldView[] {
  return DRAFT_FIELDS.map((f) => ({
    key: f.key,
    label: t(`tankCfg.${f.key}`),
    unit: f.unit === 'mm' ? t('form.mm') : f.unit === 'L' ? t('form.liter') : f.unit,
    value: draft.values[f.key] ?? '',
    // Temperatures can be below zero; the digit keyboard has no minus sign on every phone.
    signed: f.kind === 'signed',
  }));
}

function readStored(key: string): TankDraft | null {
  try {
    return readDraft(wx.getStorageSync(key));
  } catch {
    return null;
  }
}

Page({
  data: {
    text: texts('tankCfg'),
    form: texts('form'),
    state: 'loading' as State,
    notice: '',
    stationName: '',
    device: '',
    tankOptions: [] as string[],
    tankIndex: -1,
    // 设计稿「物料颜色」的 12 色色板，行优先
    colorOptions: [
      '#05507c', '#7c40aa', '#1d7cd8', '#e12c71', '#05abda', '#fe372f',
      '#0bb179', '#ff5b26', '#54c64d', '#fd8536', '#dff453', '#fde344',
    ],
    colorIndex: -1,
    // 手动输入的自由文本（汽油、柴油、大豆油…）：只在本机草稿里
    medium: '',
    fields: [] as FieldView[],
    // "上次保存 …" or "尚未保存草稿"
    savedText: '',
    pickerKind: 'tank' as 'tank' | 'color',
    pickerTop: 0,
    gridShow: false,
    sideShow: false,
  },

  tanks: [] as number[],
  // The draft as last saved (or empty), to tell whether the form has unsaved changes.
  saved: emptyDraft(),
  seq: 0,

  onShow() {
    setNavTitle('tankCfg.navTitle');
    this.setData({ text: texts('tankCfg'), form: texts('form') });
    this.resolve();
  },

  // The home page's current station and its device, decided by the same rules as 我的记录. Another device
  // than before (home switched station, or the station's device was replaced) starts from that device's
  // own drafts; nothing of the previous device's form is carried over.
  resolve() {
    const seq = ++this.seq;
    loadRecordsContext()
      .then((ctx) => {
        if (seq !== this.seq) return undefined;
        if (ctx.kind === 'no-station') {
          this.setData({ state: 'no-station', notice: t('records.noStation'), device: '', stationName: '' });
          return undefined;
        }
        if (ctx.kind !== 'ok') {
          const notice = ctx.kind === 'ambiguous' ? t(FOLLOW_NOTICES.ambiguous) : t('records.emptyStation');
          this.setData({ state: 'blocked', notice, device: '', stationName: ctx.stationName });
          return undefined;
        }
        const device = ctx.device.device_name;
        this.setData({ stationName: ctx.stationName });
        if (device === this.data.device && this.data.state === 'ok') return undefined;
        return this.loadTanks(seq, device);
      })
      .catch((err: Error) => {
        if (seq !== this.seq) return;
        this.setData({ state: 'error', notice: describeError(err), device: '' });
      });
  },

  // The tanks this user can see on the device (GET /api/devices/:device/tanks). None reported yet: there
  // is no tank to attach a draft to.
  loadTanks(seq: number, device: string): Promise<void> {
    return get<DeviceTanksResponse>(`/api/devices/${encodeURIComponent(device)}/tanks`).then((res) => {
      if (seq !== this.seq) return;
      const tanks = Array.from(new Set((res.tanks || []).map((x) => x.index))).sort((a, b) => a - b);
      this.tanks = tanks;
      if (tanks.length === 0) {
        this.setData({ state: 'no-tanks', notice: t('tankCfg.noTanks'), device, tankOptions: [], tankIndex: -1 });
        return;
      }
      this.setData({
        state: 'ok',
        notice: '',
        device,
        tankOptions: tanks.map((n) => t('tank.no', { n })),
      });
      this.openTank(0);
    });
  },

  storageKey(index: number): string {
    return draftKey(userKeyFromToken(getToken()), this.data.device, this.tanks[index]);
  },

  // Shows the draft of one tank: the saved one, or every field not set.
  openTank(index: number) {
    const draft = readStored(this.storageKey(index)) || emptyDraft();
    this.saved = draft;
    this.showDraft(draft, index);
  },

  showDraft(draft: TankDraft, index?: number) {
    this.setData({
      tankIndex: index === undefined ? (this.data.tankIndex as number) : index,
      colorIndex: draft.color,
      medium: draft.medium,
      fields: fieldViews(draft),
      savedText: draft.savedAt
        ? t('tankCfg.savedAt', { time: formatLocalTime(new Date(draft.savedAt).toISOString(), currentLocale()) })
        : t('tankCfg.noDraft'),
    });
  },

  // The form as it stands now.
  current(): TankDraft {
    const values: Record<string, string> = {};
    for (const f of this.data.fields as FieldView[]) values[f.key] = f.value;
    return { color: this.data.colorIndex as number, medium: this.data.medium as string, values, savedAt: this.saved.savedAt };
  },

  // 点选择行：量出该行 bottom，把对应的面板贴在它下面
  onOpenPicker(e: WechatMiniprogram.BaseEvent) {
    const kind = e.currentTarget.dataset.pick as 'tank' | 'color';
    wx.createSelectorQuery()
      .select(`#row-${kind}`)
      .boundingClientRect((rect) => {
        if (!rect) return;
        this.setData({ pickerKind: kind, pickerTop: rect.bottom, gridShow: kind === 'tank', sideShow: kind === 'color' });
      })
      .exec();
  },

  // Another tank: its own draft. Unsaved changes of this one are lost, so ask first.
  onPickGrid(e: WechatMiniprogram.CustomEvent<{ index: number }>) {
    const index = e.detail.index;
    this.setData({ gridShow: false });
    if (index === this.data.tankIndex) return;
    if (sameDraft(this.current(), this.saved)) {
      this.openTank(index);
      return;
    }
    wx.showModal({
      title: t('tankCfg.discardTitle'),
      content: t('tankCfg.discardBody'),
      success: (r) => {
        if (r.confirm) this.openTank(index);
      },
    });
  },

  onPickSide(e: WechatMiniprogram.CustomEvent<{ index: number }>) {
    this.setData({ colorIndex: e.detail.index, sideShow: false });
  },

  onClosePicker() {
    this.setData({ gridShow: false, sideShow: false });
  },

  onMediumInput(e: WechatMiniprogram.Input) {
    this.setData({ medium: e.detail.value });
  },

  onFieldInput(e: WechatMiniprogram.Input) {
    const key = e.currentTarget.dataset.key as string;
    const fields = (this.data.fields as FieldView[]).map((f) => (f.key === key ? { ...f, value: e.detail.value } : f));
    this.setData({ fields });
  },

  // 保存 = 保存到本机草稿. Checks the form (numbers, sign), never claims the device took it.
  onSave() {
    if (this.data.state !== 'ok' || this.data.tankIndex < 0) return;
    const draft = this.current();
    const problem = validateDraft(draft.values);
    if (problem) {
      const label = t(`tankCfg.${problem.field}`);
      wx.showToast({ title: t(`tankCfg.bad.${problem.reason}`, { field: label }), icon: 'none', duration: 3000 });
      return;
    }
    const toSave: TankDraft = { ...draft, savedAt: Date.now() };
    try {
      wx.setStorageSync(this.storageKey(this.data.tankIndex as number), toSave);
    } catch {
      wx.showToast({ title: t('tankCfg.saveFailed'), icon: 'none' });
      return;
    }
    this.saved = toSave;
    this.showDraft(toSave);
    wx.showToast({ title: t('tankCfg.savedDraft'), icon: 'none', duration: 3000 });
  },

  // 恢复默认: the form of this tank goes back to "not set". It changes this phone's form only (保存 then
  // keeps it), and it never resets anything on the controller.
  onReset() {
    if (this.data.state !== 'ok') return;
    this.showDraft({ ...emptyDraft(), savedAt: this.saved.savedAt });
    wx.showToast({ title: t('tankCfg.resetDone'), icon: 'none', duration: 3000 });
  },

  onGoHome() {
    wx.switchTab({ url: '/pages/devices/devices' });
  },

  onRetry() {
    this.resolve();
  },
});

export {};
