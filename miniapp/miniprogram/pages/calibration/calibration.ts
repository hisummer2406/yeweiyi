import { roleFromToken } from '../../utils/auth';
import { confirmAndSend } from '../../utils/configSubmit';
import { describeError } from '../../utils/errors';
import { formatLocalTime } from '../../utils/format';
import { currentLocale, setNavTitle, t, texts } from '../../utils/i18n';
import { CORRECTION_SEND_FIELDS, buildArgs, mirrorToForm, toggleSign } from '../../utils/remoteConfig';
import { getTankSettings } from '../../utils/remoteConfigApi';
import { get } from '../../utils/request';
import { isReadOnlyRole } from '../../utils/roles';
import { FOLLOW_NOTICES } from '../../utils/stationDevice';
import { loadRecordsContext } from '../../utils/stationPref';
import { getToken } from '../../utils/storage';
import type { DeviceTanksResponse, TankSettings } from '../../types/api';

// 参数修正（#6）：传感器读数的校准偏移量（液位/水位/温度修正），不是修正已有记录。
// 对象与储罐设置相同：首页当前油站 → 其唯一设备 → 当前账号可见的罐。上方显示设备当前修正值（云端镜像）；
// 「保存到手机」只存本机（同一 key，退出登录清除，且只在同一设备同一罐时读回）；「保存到控制器」= 管理员直接
// 下发 config.setTankSettings 的三项修正，只发与设备回报不同的值。员工只能看。

const STORAGE_KEY = 'calibration-draft';
const DASH = '—';

type State = 'loading' | 'ok' | 'no-station' | 'blocked' | 'no-tanks' | 'error';
type Saved = { device?: string; tank?: number; levelOffset?: string; waterOffset?: string; tempOffset?: string };

Page({
  data: {
    // 「下发」流程进行中（确认框、发送、查回、同编号重试）：按钮禁用并显示加载，#18 F4
    sending: false,
    text: texts('calibration'),
    form: texts('form'),
    state: 'loading' as State,
    notice: '',
    stationName: '',
    device: '',
    tankOptions: [] as string[],
    tankIndex: -1,
    levelOffset: '',
    waterOffset: '',
    tempOffset: '',
    reason: '',
    // 设备当前修正值
    current: { levelOffset: DASH, waterOffset: DASH, tempOffset: DASH } as Record<string, string>,
    currentTime: '',
    mirrorKnown: false,
    canSend: false,
    sendHint: '',
    pickerShow: false,
    pickerTop: 0,
  },

  tanks: [] as number[],
  mirrors: [] as TankSettings[],
  mirrorLoaded: false,
  stationId: null as number | null,
  seq: 0,

  onShow() {
    setNavTitle('calibration.navTitle');
    this.setData({ text: texts('calibration'), form: texts('form') });
    this.resolve();
  },

  resolve() {
    const seq = ++this.seq;
    loadRecordsContext()
      .then((ctx) => {
        if (seq !== this.seq) return undefined;
        if (ctx.kind === 'no-station') {
          this.setData({ state: 'no-station', notice: t('records.noStation'), device: '' });
          return undefined;
        }
        if (ctx.kind !== 'ok') {
          const notice = ctx.kind === 'ambiguous' ? t(FOLLOW_NOTICES.ambiguous) : t('records.emptyStation');
          this.setData({ state: 'blocked', notice, device: '', stationName: ctx.stationName });
          return undefined;
        }
        const device = ctx.device.device_name;
        this.stationId = ctx.device.station_id ?? null;
        this.setData({ stationName: ctx.stationName });
        if (device === this.data.device && this.data.state === 'ok') {
          // Same device: keep what is typed, re-read the device's corrections (Codex review, PR #15)
          getTankSettings(device)
            .then((m) => {
              if (seq !== this.seq) return;
              this.mirrors = m;
              this.mirrorLoaded = true;
              this.showCurrent();
            })
            .catch(() => {
              if (seq !== this.seq) return;
              this.updateSendable();
            });
          return undefined;
        }
        return get<DeviceTanksResponse>(`/api/devices/${encodeURIComponent(device)}/tanks`).then((res) => {
          if (seq !== this.seq) return;
          const tanks = Array.from(new Set((res.tanks || []).map((x) => x.index))).sort((a, b) => a - b);
          this.tanks = tanks;
          if (tanks.length === 0) {
            this.setData({ state: 'no-tanks', notice: t('tankCfg.noTanks'), device, tankOptions: [], tankIndex: -1 });
            return;
          }
          this.setData({ state: 'ok', notice: '', device, tankOptions: tanks.map((n) => t('tank.no', { n })) });
          this.mirrorLoaded = false;
          this.openTank(0);
          getTankSettings(device)
            .then((m) => {
              if (seq !== this.seq) return;
              this.mirrors = m;
              this.mirrorLoaded = true;
              this.showCurrent();
            })
            .catch(() => {
              if (seq !== this.seq) return;
              this.mirrors = [];
              this.mirrorLoaded = false;
              this.setData({ currentTime: t('tankCfg.deviceLoadFailed') });
              this.updateSendable();
            });
        });
      })
      .catch((err: Error) => {
        if (seq !== this.seq) return;
        this.setData({ state: 'error', notice: describeError(err), device: '' });
      });
  },

  mirror(): TankSettings | undefined {
    const tank = this.tanks[this.data.tankIndex as number];
    return this.mirrors.find((m) => m.tank === tank);
  },

  // The phone's saved values belong to one device and one tank; anything else opens empty.
  openTank(index: number) {
    let saved: Saved = {};
    try {
      saved = (wx.getStorageSync(STORAGE_KEY) as Saved) || {};
    } catch {
      saved = {};
    }
    const same = saved.device === this.data.device && saved.tank === this.tanks[index];
    this.setData({
      tankIndex: index,
      levelOffset: same ? saved.levelOffset || '' : '',
      waterOffset: same ? saved.waterOffset || '' : '',
      tempOffset: same ? saved.tempOffset || '' : '',
    });
    if (this.mirrorLoaded) this.showCurrent();
  },

  showCurrent() {
    const m = this.mirror();
    const form = mirrorToForm(CORRECTION_SEND_FIELDS, m as unknown as Record<string, unknown>);
    this.setData({
      current: {
        levelOffset: form.levelOffset || DASH,
        waterOffset: form.waterOffset || DASH,
        tempOffset: form.tempOffset || DASH,
      },
      mirrorKnown: !!m,
      currentTime: m
        ? t('tankCfg.deviceReportedAt', { time: formatLocalTime(m.reportedAt, currentLocale()) })
        : t('tankCfg.deviceNotReported'),
    });
    this.updateSendable();
  },

  updateSendable() {
    const token = getToken();
    const admin = !!token && !isReadOnlyRole(roleFromToken(token));
    this.setData({
      // 没有逐台开放（2026-11-09）：管理员直接下发；系统总闸关闭时云端答 config_commands_disabled
      canSend: admin && this.mirrorLoaded,
      sendHint: '',
    });
  },

  onOpenTank() {
    wx.createSelectorQuery()
      .select('#row-tank')
      .boundingClientRect((rect) => {
        if (rect) this.setData({ pickerShow: true, pickerTop: rect.bottom });
      })
      .exec();
  },
  onPickTank(e: WechatMiniprogram.CustomEvent<{ index: number }>) {
    this.setData({ pickerShow: false });
    this.openTank(e.detail.index);
  },
  onClosePicker() {
    this.setData({ pickerShow: false });
  },
  // 正负号切换（#30）：部分手机键盘打不出负号
  onToggleSign(e: WechatMiniprogram.BaseEvent) {
    const field = e.currentTarget.dataset.field as 'levelOffset' | 'waterOffset' | 'tempOffset';
    this.setData({ [field]: toggleSign(String(this.data[field] ?? '')) });
  },
  onLevelInput(e: WechatMiniprogram.Input) {
    this.setData({ levelOffset: e.detail.value });
  },
  onWaterInput(e: WechatMiniprogram.Input) {
    this.setData({ waterOffset: e.detail.value });
  },
  onTempInput(e: WechatMiniprogram.Input) {
    this.setData({ tempOffset: e.detail.value });
  },
  onReasonInput(e: WechatMiniprogram.Input) {
    this.setData({ reason: e.detail.value });
  },

  onImport() {
    const form = mirrorToForm(CORRECTION_SEND_FIELDS, this.mirror() as unknown as Record<string, unknown>);
    this.setData({ levelOffset: form.levelOffset, waterOffset: form.waterOffset, tempOffset: form.tempOffset });
    wx.showToast({ title: t('tankCfg.imported'), icon: 'none' });
  },

  onSaveLocal() {
    if (this.data.state !== 'ok') return;
    const { levelOffset, waterOffset, tempOffset } = this.data;
    const saved: Saved = { device: this.data.device, tank: this.tanks[this.data.tankIndex as number], levelOffset, waterOffset, tempOffset };
    wx.setStorageSync(STORAGE_KEY, saved);
    wx.showToast({ title: t('form.savedLocal'), icon: 'none' });
  },

  // 保存到控制器 = 下发三项修正（只发与设备不同的值）
  onSaveDevice() {
    if (this.data.sending) return;
    if (!this.data.canSend) {
      wx.showToast({ title: this.data.sendHint || t('cfgSend.notAllowed'), icon: 'none', duration: 3000 });
      return;
    }
    const values = { levelOffset: this.data.levelOffset, waterOffset: this.data.waterOffset, tempOffset: this.data.tempOffset };
    const mirror = this.mirror() as unknown as Record<string, unknown> | undefined;
    const { args, problems } = buildArgs(CORRECTION_SEND_FIELDS, values, mirror);
    if (problems.length) {
      const p = problems[0];
      wx.showToast({ title: t(`cfgSend.bad.${p.reason}`, { field: t(`calibration.${p.form}`) }), icon: 'none', duration: 3000 });
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
    const lines = CORRECTION_SEND_FIELDS.filter((f) => f.cloud in args).map((f) => {
      const before = mirror ? mirror[f.cloud] : undefined;
      return { label: t(`calibration.${f.form}`), before: typeof before === 'number' ? String(before) : DASH, after: String(args[f.cloud]) };
    });
    this.setData({ sending: true });
    confirmAndSend({
      device: this.data.device as string,
      stationId: this.stationId,
      tank: this.tanks[this.data.tankIndex as number],
      args,
      reason,
      lines,
    }).finally(() => this.setData({ sending: false }));
  },

  onGoHome() {
    wx.switchTab({ url: '/pages/devices/devices' });
  },
  onRetry() {
    this.resolve();
  },
});

export {};
