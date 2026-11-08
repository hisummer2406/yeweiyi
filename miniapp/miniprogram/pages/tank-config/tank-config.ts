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
import type { DeviceTanksResponse, TankSettings } from '../../types/api';
import { roleFromToken } from '../../utils/auth';
import { isReadOnlyRole } from '../../utils/roles';
import { TANK_DISPLAY_ONLY, TANK_SEND_FIELDS, buildArgs, mirrorToForm } from '../../utils/remoteConfig';
import { getTankSettings } from '../../utils/remoteConfigApi';
import { confirmAndSend } from '../../utils/configSubmit';

// 储罐设置（miniapp#18，#6）：按设计稿排的表单。「本机草稿」只保存在本手机；「设备当前回报」来自云端镜像
// （GET /api/devices/:device/tank-settings），两者分开显示，草稿只能由「导入设备值」明确导入。管理员在设备已开放
// 远程配置时可「下发到设备」：只发与设备回报不同、且云端开放的字段（utils/remoteConfig.ts TANK_SEND_FIELDS）；
// 密度、罐高、低水位、低温、物料颜色、介质文本不下发。员工没有下发入口。
//
// The object being edited is shown on top — the home page's current station, its one device, and one of
// the tanks this user can see on it (not a fixed 1–12 list). 保存 writes a draft keyed by account + device
// + tank (utils/tankDraft.ts); it is not sent anywhere, and the controller's real settings are neither read
// nor changed: that is cloud#70 (read) / #71 (write). The only requests here are the ones that decide the
// station, the device and its tanks.

type State = 'loading' | 'ok' | 'no-station' | 'blocked' | 'no-tanks' | 'error';

type FieldView = { key: string; label: string; unit: string; value: string; signed: boolean };
type MirrorRow = { key: string; label: string; value: string };
type MirrorState = 'loading' | 'ok' | 'none' | 'error';

const DASH = '—';

// The device's value of one draft field, as text. Fields the device has no such setting for say so.
function mirrorRows(m: TankSettings | undefined): MirrorRow[] {
  return DRAFT_FIELDS.map((f) => {
    const send = TANK_SEND_FIELDS.find((x) => x.form === f.key);
    const cloud = send ? send.cloud : TANK_DISPLAY_ONLY[f.key];
    let value = t('tankCfg.deviceNone');
    if (cloud) {
      const v = m ? (m as unknown as Record<string, unknown>)[cloud] : undefined;
      value = typeof v === 'number' ? String(v) : DASH;
    }
    return { key: f.key, label: t(`tankCfg.${f.key}`), value };
  });
}

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
    // 设备当前回报（云端镜像）
    mirrorState: 'loading' as MirrorState,
    mirrorRows: [] as MirrorRow[],
    mirrorTime: '',
    // 管理员 + 设备已开放：可下发
    canSend: false,
    // 管理员但设备未开放（或旧云端）：说明原因
    sendHint: '',
    reason: '',
    pickerKind: 'tank' as 'tank' | 'color',
    pickerTop: 0,
    gridShow: false,
    sideShow: false,
  },

  tanks: [] as number[],
  mirrors: [] as TankSettings[],
  stationId: null as number | null,
  deviceRow: undefined as { config_commands_enabled?: boolean } | undefined,
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
        this.stationId = ctx.device.station_id ?? null;
        this.deviceRow = ctx.device;
        this.setData({ stationName: ctx.stationName });
        this.updateSendable();
        if (device === this.data.device && this.data.state === 'ok') {
          // Same device (e.g. back from 操作记录 after a send): keep the draft, but re-read the device's report so
          // the next comparison is against what the device has now, not the first load (Codex review, PR #15).
          this.loadMirror(seq, device);
          return undefined;
        }
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
      this.setData({ mirrorState: 'loading' });
      this.openTank(0);
      this.loadMirror(seq, device);
    });
  },

  // 设备当前回报。读失败不影响草稿；下发需要它作为比较基准。
  loadMirror(seq: number, device: string) {
    getTankSettings(device)
      .then((tanks) => {
        if (seq !== this.seq) return;
        this.mirrors = tanks;
        this.showMirror();
      })
      .catch(() => {
        if (seq !== this.seq) return;
        this.mirrors = [];
        this.setData({ mirrorState: 'error', mirrorRows: [], mirrorTime: '' });
        this.updateSendable();
      });
  },

  mirror(): TankSettings | undefined {
    const tank = this.tanks[this.data.tankIndex as number];
    return this.mirrors.find((m) => m.tank === tank);
  },

  showMirror() {
    const m = this.mirror();
    this.setData({
      mirrorState: m ? 'ok' : 'none',
      mirrorRows: mirrorRows(m),
      mirrorTime: m ? t('tankCfg.deviceReportedAt', { time: formatLocalTime(m.reportedAt, currentLocale()) }) : '',
    });
    this.updateSendable();
  },

  updateSendable() {
    const token = getToken();
    const admin = !!token && !isReadOnlyRole(roleFromToken(token));
    const gate = this.deviceRow ? this.deviceRow.config_commands_enabled : undefined;
    const mirrorReady = this.data.mirrorState === 'ok' || this.data.mirrorState === 'none';
    this.setData({
      canSend: admin && gate === true && mirrorReady,
      sendHint: !admin ? '' : gate === undefined ? t('tankCfg.gateUnknown') : gate ? '' : t('tankCfg.gateClosed'),
    });
  },

  // 导入设备值：把设备回报填进表单（只填可下发的字段）；不保存，用户确认后再保存或下发。
  onImport() {
    const m = this.mirror();
    if (!m) return;
    const imported = mirrorToForm(TANK_SEND_FIELDS, m as unknown as Record<string, unknown>);
    const fields = (this.data.fields as FieldView[]).map((f) => (imported[f.key] ? { ...f, value: imported[f.key] } : f));
    this.setData({ fields });
    wx.showToast({ title: t('tankCfg.imported'), icon: 'none' });
  },

  onReasonInput(e: WechatMiniprogram.Input) {
    this.setData({ reason: e.detail.value });
  },

  // 下发到设备：只发与设备回报不同的开放字段。
  onSend() {
    if (!this.data.canSend || this.data.tankIndex < 0) return;
    const values: Record<string, string> = {};
    for (const f of this.data.fields as FieldView[]) values[f.key] = f.value;
    const mirror = this.mirror() as unknown as Record<string, unknown> | undefined;
    const { args, problems } = buildArgs(TANK_SEND_FIELDS, values, mirror);
    if (problems.length) {
      const p = problems[0];
      wx.showToast({ title: t(`cfgSend.bad.${p.reason}`, { field: t(`tankCfg.${p.form}`) }), icon: 'none', duration: 3000 });
      return;
    }
    if (Object.keys(args).length === 0) {
      wx.showToast({ title: t('cfgSend.nothing'), icon: 'none', duration: 3000 });
      return;
    }
    const reason = (this.data.reason as string).trim();
    if (!reason) {
      wx.showToast({ title: t('cfgSend.needReason'), icon: 'none' });
      return;
    }
    const lines = TANK_SEND_FIELDS.filter((f) => f.cloud in args).map((f) => {
      const before = mirror ? mirror[f.cloud] : undefined;
      return { label: t(`tankCfg.${f.form}`), before: typeof before === 'number' ? String(before) : DASH, after: String(args[f.cloud]) };
    });
    confirmAndSend({
      device: this.data.device as string,
      stationId: this.stationId,
      tank: this.tanks[this.data.tankIndex as number],
      args,
      reason,
      lines,
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
    if (this.data.mirrorState !== 'loading') this.showMirror();
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
