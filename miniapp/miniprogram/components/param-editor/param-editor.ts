import { roleFromToken, userKeyFromToken } from '../../utils/auth';
import { confirmAndSend } from '../../utils/configSubmit';
import { formatLocalTime } from '../../utils/format';
import { currentLocale, t, texts } from '../../utils/i18n';
import {
  applyInput,
  hasEdits,
  mergeReport,
  paramDraftKey,
  readParamDraft,
  toDraft,
  type DraftTarget,
  type Merged,
} from '../../utils/paramDraft';
import { mediumText } from '../../utils/recordColumns';
import {
  TANK_READONLY,
  buildArgs,
  buildMediumArgs,
  ignoredFields,
  commandError,
  commandPhase,
  echoState,
  editFields,
  mirrorToForm,
  toggleSign,
  type EditField,
  type Echo,
  type ParamKind,
  type Unit,
} from '../../utils/remoteConfig';
import { getCommand, getMediaOptions, getTankSettings } from '../../utils/remoteConfigApi';
import { OIL_COLOR_PALETTE, isPaletteColor, toCss } from '../../utils/oilColor';
import { ApiError } from '../../utils/request';
import { isReadOnlyRole } from '../../utils/roles';
import { getToken } from '../../utils/storage';
import type { ControlCommand, MediaOption, TankSettings } from '../../types/api';

// 参数修正 / 储罐设置 的编辑表单（Issue #43）。罐详情的两个参数页签和设置页里的两个旧入口都用它，规则只有一套：
// - 对象由使用方给定（device + 实际罐号 + 设备行的 station_id）；组件自己不选设备、不选罐。
// - 自动带入设备最近上报值（tank-settings 镜像）并显示镜像的上报时间；缺失 = 未上报，不补 0。
// - 只有与上报值不同的输入算修改，标「原值 → 新值」；修改自动暂存（utils/paramDraft.ts，按种类+账号+设备+罐隔离）。
// - 刷新镜像不覆盖输入：未改字段跟随新值，已改字段保留，其上报值变了就提示复核。
// - 「下发修改」只发改动且合法的字段（buildArgs），原因必填，确认框列前后值与单位；受理后在本组件内跟踪结果：
//   terminal 之前是处理中，成功后按 echoState 核对新鲜回显；拒绝/失败显示设备原因（#42）；过期 = 结果无法确认。
// - 员工（customer_user）只看上报值，没有草稿和下发。
// - 储罐设置另有介质（选 mediaId，云端换算）与颜色（20 色色板）两项选择（2026-11-14），只有管理员可选；
//   选项读不到时介质只读，固件不支持（media-options.oilColorSync 为假）时颜色只读。选择不进暂存草稿。

const POLL_MS = 2000;
const ECHO_MS = 5000;
const ECHO_WAIT_MS = 3 * 60 * 1000;
// station 属性：-1 = 设备不在任何油站，-2 = 还不知道（不能下发）
const STATION_NONE = -1;

type LoadState = 'idle' | 'loading' | 'ok' | 'none' | 'error';
type Row = {
  key: string;
  label: string;
  unit: string;
  signed: boolean;
  value: string;
  changed: boolean;
  // 「原值 → 新值」/「设备值已变化」/「留空 = 不修改」之一，'' = 不显示
  note: string;
  warn: boolean;
};
type RoRow = { key: string; label: string; value: string; unit: string };
type Result = { tone: 'info' | 'ok' | 'warn' | 'bad'; message: string; error: string; commandId: string };
type Tracked = { device: string; tank: number; cmd: ControlCommand; echo?: Echo; echoStarted: number };

function unitText(u: Unit): string {
  if (u === 'mm') return t('form.mm');
  if (u === 'L') return t('form.liter');
  return u;
}

const PALETTE = OIL_COLOR_PALETTE.map((c) => ({ color: c, css: toCss(c) }));

function shown(v: string): string {
  return v === '' ? t('param.unreported') : v;
}

Component({
  options: { addGlobalClass: true },

  properties: {
    kind: { type: String, value: 'calibration' },
    device: { type: String, value: '' },
    tank: { type: Number, value: -1 },
    station: { type: Number, value: -2 },
    // 首次激活才读取；页签隐藏时保持状态
    active: { type: Boolean, value: false },
  },

  data: {
    text: texts('param'),
    state: 'idle' as LoadState,
    reportedText: '',
    refreshFailed: false,
    rows: [] as Row[],
    roRows: [] as RoRow[],
    medium: '',
    mediumEditable: false,
    mediumNames: [] as string[],
    mediumIdx: -1,
    mediumNote: '',
    colors: PALETTE,
    colorEditable: false,
    colorSel: -1,
    colorNow: '',
    colorNonStandard: false,
    colorNote: '',
    isTank: false,
    readOnly: true,
    draftNote: '',
    moved: false,
    changedCount: 0,
    reason: '',
    canSend: false,
    sendBlock: '',
    sending: false,
    result: null as Result | null,
  },

  observers: {
    'kind, device, tank, active': function () {
      (this as unknown as { sync(): void }).sync();
    },
    // 设备所在油站可能在编辑器读完上报值之后才查到：只重算能否下发，不动草稿
    station: function () {
      (this as unknown as { updateSendable(): void }).updateSendable();
    },
  },

  lifetimes: {
    created() {
      const self = this as unknown as EditorThis;
      self.loadedKey = '';
      self.gen = 0;
      self.fresh = true;
      self.report = {};
      self.mirror = undefined;
      self.form = { values: {}, edits: {}, base: {}, moved: [] };
      self.tracked = undefined;
      self.trackSeq = 0;
      self.timer = 0;
      self.mediaIds = [];
    },
    detached() {
      const self = this as unknown as EditorThis;
      self.gen++;
      self.stopTrack();
    },
  },

  pageLifetimes: {
    show() {
      const self = this as unknown as EditorThis;
      self.setData({ text: texts('param') });
      // 回到前台/从操作记录返回：重读上报值（不覆盖输入），继续跟踪未结束的结果
      if (self.loadedKey) self.refresh();
      self.resumeTrack();
    },
    hide() {
      (this as unknown as EditorThis).stopTrack();
    },
  },

  methods: {
    target(): DraftTarget {
      return {
        kind: this.data.kind as ParamKind,
        user: userKeyFromToken(getToken()),
        device: this.data.device as string,
        tank: this.data.tank as number,
      };
    },

    keyOf(): string {
      const x = this.target();
      return `${x.kind}|${x.user}|${x.device}|${x.tank}`;
    },

    // The object changed (or first activation): start over for it. Nothing of another tank/device/account is kept.
    sync() {
      const self = this as unknown as EditorThis;
      if (!this.data.active || !this.data.device || (this.data.tank as number) < 0) return;
      const key = this.keyOf();
      if (key === self.loadedKey) return;
      self.loadedKey = key;
      self.gen++;
      self.report = {};
      self.mirror = undefined;
      self.form = { values: {}, edits: {}, base: {}, moved: [] };
      self.fresh = true;
      const token = getToken();
      const readOnly = !token || isReadOnlyRole(roleFromToken(token));
      this.setData({
        state: 'loading',
        isTank: this.data.kind === 'tank',
        readOnly,
        reportedText: '',
        refreshFailed: false,
        rows: [],
        roRows: [],
        medium: '',
        mediumEditable: false,
        mediumNames: [],
        mediumIdx: -1,
        mediumNote: '',
        colorEditable: false,
        colorSel: -1,
        colorNow: '',
        colorNonStandard: false,
        colorNote: '',
        draftNote: '',
        moved: false,
        changedCount: 0,
        reason: '',
      });
      this.showResult();
      this.load();
    },

    // Public: re-read the device's report (pull-down, page shown again).
    refresh() {
      const self = this as unknown as EditorThis;
      if (!self.loadedKey || this.keyOf() !== self.loadedKey) {
        this.sync();
        return;
      }
      this.load();
    },

    load() {
      const self = this as unknown as EditorThis;
      const gen = ++self.gen;
      const device = this.data.device as string;
      const tank = this.data.tank as number;
      if (this.data.kind === 'tank' && !this.data.readOnly) this.loadOptions(gen);
      getTankSettings(device)
        .then((rows) => {
          if (gen !== self.gen) return;
          this.applyMirror(rows.find((m) => m.tank === tank));
        })
        .catch(() => {
          if (gen !== self.gen) return;
          // Something already shown: keep it (and the input), say it may be old. Nothing yet: say reading failed;
          // an empty form must not pass for the device's configuration, and nothing can be sent against it.
          if (this.data.state === 'ok' || this.data.state === 'none') this.setData({ refreshFailed: true });
          else this.setData({ state: 'error' });
          this.updateSendable();
        });
    },

    // Administrators only: what the medium can be changed to, and whether the firmware takes a colour.
    loadOptions(gen: number) {
      const self = this as unknown as EditorThis;
      getMediaOptions(this.data.device as string)
        .then((o) => {
          if (gen !== self.gen) return;
          const list: MediaOption[] = [...(o.public || []), ...(o.customer || [])];
          const keep = self.mediaIds[this.data.mediumIdx as number];
          self.mediaIds = list.map((x) => x.id);
          this.setData({
            mediumEditable: true,
            mediumNames: list.map((x) => x.name),
            mediumIdx: keep === undefined ? -1 : self.mediaIds.indexOf(keep),
            mediumNote: '',
            colorEditable: !!o.oilColorSync,
            colorSel: o.oilColorSync ? this.data.colorSel : -1,
            colorNote: o.oilColorSync ? '' : t('param.colorUnsupported'),
          });
          this.render();
        })
        .catch(() => {
          if (gen !== self.gen) return;
          self.mediaIds = [];
          this.setData({ mediumEditable: false, mediumIdx: -1, mediumNote: t('param.mediumUnavailable'), colorEditable: false, colorSel: -1, colorNote: '' });
          this.render();
        });
    },

    onMediumPick(e: WechatMiniprogram.PickerChange) {
      if (this.data.readOnly || !this.data.mediumEditable) return;
      this.setData({ mediumIdx: Number(e.detail.value) });
      this.render();
    },

    // Tap a colour to choose it; tap it again to leave the colour as it is.
    onColorPick(e: WechatMiniprogram.BaseEvent) {
      if (this.data.readOnly || !this.data.colorEditable) return;
      const c = Number(e.currentTarget.dataset.color);
      this.setData({ colorSel: this.data.colorSel === c ? -1 : c });
      this.render();
    },

    // The medium/colour choices as cloud args (only what differs from the report).
    choiceArgs(): Record<string, number> {
      const self = this as unknown as EditorThis;
      const idx = this.data.mediumIdx as number;
      const sel = this.data.colorSel as number;
      return buildMediumArgs(
        idx >= 0 ? self.mediaIds[idx] : undefined,
        sel >= 0 ? sel : undefined,
        self.mirror as unknown as Record<string, unknown> | undefined,
      );
    },

    applyMirror(m: TankSettings | undefined) {
      const self = this as unknown as EditorThis;
      const fields = editFields(this.data.kind as ParamKind);
      self.mirror = m;
      self.report = mirrorToForm(fields, m as unknown as Record<string, unknown>);
      let draftNote = this.data.draftNote as string;
      if (self.fresh) {
        self.fresh = false;
        const draft = this.data.readOnly ? null : this.readDraft();
        self.form = mergeReport(fields, self.report, draft ? draft.edits : {}, draft ? draft.base : {});
        draftNote = hasEdits(self.form) && draft
          ? t('param.draftRestored', { time: formatLocalTime(new Date(draft.savedAt).toISOString(), currentLocale()) })
          : '';
      } else {
        self.form = mergeReport(fields, self.report, self.form.edits, self.form.base);
        if (!hasEdits(self.form)) draftNote = '';
      }
      this.persist();
      const ro = m as unknown as Record<string, unknown> | undefined;
      this.setData({
        state: m ? 'ok' : 'none',
        refreshFailed: false,
        reportedText: m ? t('param.reportedAt', { time: formatLocalTime(m.reportedAt, currentLocale()) }) : t('param.notReported'),
        draftNote,
        roRows: this.data.kind === 'tank'
          ? TANK_READONLY.map((f) => {
            const v = ro ? ro[f.cloud] : undefined;
            return { key: f.form, label: t(`tankCfg.${f.form}`), value: typeof v === 'number' ? String(v) : t('param.unreported'), unit: unitText(f.unit) };
          })
          : [],
        medium: this.data.kind === 'tank' && m ? mediumText(m.oilTypeIndex, m.oilType, t) : '',
        colorNow: m && typeof m.oilColor === 'number' ? toCss(m.oilColor) : '',
        colorNonStandard: !!m && typeof m.oilColor === 'number' && !isPaletteColor(m.oilColor),
      });
      this.render();
    },

    readDraft() {
      try {
        const x = this.target();
        return readParamDraft(wx.getStorageSync(paramDraftKey(x.kind, x.user, x.device, x.tank)), x);
      } catch {
        return null;
      }
    },

    // Auto-save: the edits (with the report they were made against) or nothing.
    persist() {
      const self = this as unknown as EditorThis;
      if (this.data.readOnly) return;
      const x = this.target();
      const key = paramDraftKey(x.kind, x.user, x.device, x.tank);
      try {
        if (hasEdits(self.form)) wx.setStorageSync(key, toDraft(x, self.form, Date.now()));
        else wx.removeStorageSync(key);
      } catch {
        // storage full or unavailable: the form on screen is still there
      }
    },

    render() {
      const self = this as unknown as EditorThis;
      const fields = editFields(this.data.kind as ParamKind);
      const rows: Row[] = fields.map((f: EditField) => {
        const value = self.form.values[f.form] ?? '';
        const report = self.report[f.form] ?? '';
        const changed = f.form in self.form.edits;
        const moved = self.form.moved.includes(f.form);
        const unit = unitText(f.unit);
        let note = '';
        if (moved) note = t('param.moved', { was: shown(self.form.base[f.form] ?? ''), now: shown(report), unit });
        else if (changed) note = t('param.change', { before: shown(report), after: value, unit });
        else if (value.trim() === '' && report !== '') note = t('param.keep', { before: report, unit });
        // A density above 2 g/cm³ was most likely typed in kg/m³ (the device's old label): ask to check it.
        const odd = f.form === 'density' && !changed && Number(report) > 2;
        if (odd) note = t('param.densityCheck');
        return { key: f.form, label: t(f.labelKey), unit, signed: f.signed, value, changed, note, warn: moved || odd };
      });
      const choices = Object.keys(this.choiceArgs()).length;
      this.setData({ rows, moved: self.form.moved.length > 0, changedCount: Object.keys(self.form.edits).length + choices });
      this.updateSendable();
    },

    updateSendable() {
      const ready = this.data.state === 'ok' || this.data.state === 'none';
      const stationKnown = (this.data.station as number) >= STATION_NONE;
      let sendBlock = '';
      if (!this.data.readOnly && ready && !stationKnown) sendBlock = t('param.stationUnknown');
      this.setData({ canSend: !this.data.readOnly && ready && stationKnown, sendBlock });
    },

    // Public: unsent edits on screen.
    hasEdits(): boolean {
      return hasEdits((this as unknown as EditorThis).form);
    },

    // Public: drop the edits of this tank; the form goes back to the device's report.
    discardDraft() {
      const self = this as unknown as EditorThis;
      const fields = editFields(this.data.kind as ParamKind);
      self.form = mergeReport(fields, self.report, {}, {});
      this.persist();
      this.setData({ draftNote: '', mediumIdx: -1, colorSel: -1 });
      this.render();
    },

    onDiscard() {
      this.discardDraft();
    },

    onInput(e: WechatMiniprogram.Input) {
      this.setInput(e.currentTarget.dataset.key as string, e.detail.value);
    },

    // ± (#30): some phone keyboards have no usable minus
    onToggleSign(e: WechatMiniprogram.BaseEvent) {
      const self = this as unknown as EditorThis;
      const key = e.currentTarget.dataset.key as string;
      this.setInput(key, toggleSign(self.form.values[key] ?? ''));
    },

    setInput(key: string, value: string) {
      const self = this as unknown as EditorThis;
      if (this.data.readOnly) return;
      self.form = applyInput(self.form, key, value, self.report);
      this.persist();
      this.render();
    },

    onReasonInput(e: WechatMiniprogram.Input) {
      this.setData({ reason: e.detail.value });
    },

    onRetry() {
      this.load();
    },

    onSend() {
      const self = this as unknown as EditorThis;
      if (this.data.sending || !this.data.canSend) return;
      const kind = this.data.kind as ParamKind;
      const fields = editFields(kind);
      const mirror = self.mirror as unknown as Record<string, unknown> | undefined;
      const built = buildArgs(fields, self.form.values, mirror);
      const { problems } = built;
      const choices = kind === 'tank' ? this.choiceArgs() : {};
      const args = { ...built.args, ...choices };
      if (problems.length) {
        const p = problems[0];
        const f = fields.find((x) => x.form === p.form);
        wx.showToast({ title: t(`cfgSend.bad.${p.reason}`, { field: f ? t(f.labelKey) : p.form }), icon: 'none', duration: 3000 });
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
      const lines = fields.filter((f) => f.cloud in args).map((f) => {
        const unit = unitText(f.unit);
        const before = self.report[f.form] ?? '';
        return { label: t(f.labelKey), before: before === '' ? t('param.unreported') : `${before} ${unit}`, after: `${args[f.cloud]} ${unit}` };
      });
      if ('mediaId' in choices) {
        lines.push({ label: t('param.medium'), before: (this.data.medium as string) || t('param.unreported'), after: (this.data.mediumNames as string[])[this.data.mediumIdx as number] });
      }
      if ('oilColor' in choices) {
        lines.push({ label: t('param.color'), before: (this.data.colorNow as string) || t('param.unreported'), after: toCss(choices.oilColor) });
      }
      const device = this.data.device as string;
      const tank = this.data.tank as number;
      const station = this.data.station as number;
      const key = self.loadedKey;
      this.setData({ sending: true });
      confirmAndSend({ device, stationId: station >= 0 ? station : null, tank, args, reason, lines, stay: true })
        .then((cmd) => {
          if (!cmd) return;
          // The result belongs to that device + tank, whatever is on screen by now.
          this.track({ device, tank, cmd, echoStarted: 0 });
          // The choices went out with this command; the device's next report shows them.
          if (key === self.loadedKey) {
            this.setData({ reason: '', mediumIdx: -1, colorSel: -1 });
            this.render();
          }
        })
        .finally(() => this.setData({ sending: false }));
    },

    // ---- 本次下发的结果 ----

    track(tr: Tracked) {
      const self = this as unknown as EditorThis;
      self.stopTrack();
      if (this.handOff(tr)) return;
      // 超时找回/同请求重试可能直接拿到已成功的命令：同样要等设备新上报
      if (tr.cmd.terminal && tr.cmd.status === 'succeeded' && !tr.echo) {
        tr.echo = 'waiting';
        tr.echoStarted = Date.now();
      }
      self.tracked = tr;
      this.showResult();
      this.tick(++self.trackSeq);
    },

    resumeTrack() {
      const self = this as unknown as EditorThis;
      const tr = self.tracked;
      if (!tr) return;
      if (tr.cmd.terminal && !(tr.cmd.status === 'succeeded' && tr.echo === 'waiting' && Date.now() - tr.echoStarted < ECHO_WAIT_MS)) return;
      this.tick(++self.trackSeq);
    },

    stopTrack() {
      const self = this as unknown as EditorThis;
      self.trackSeq++;
      if (self.timer) clearTimeout(self.timer);
      self.timer = 0;
    },

    later(seq: number, ms: number) {
      const self = this as unknown as EditorThis;
      if (self.timer) clearTimeout(self.timer);
      self.timer = setTimeout(() => this.tick(seq), ms) as unknown as number;
    },

    // The command until terminal (the cloud's flag, not a status list), then — succeeded only — the device's
    // next report: strictly later than finishedAt and equal = 已一致 (echoState).
    tick(seq: number) {
      const self = this as unknown as EditorThis;
      const tr = self.tracked;
      if (seq !== self.trackSeq || !tr) return;
      if (!tr.cmd.terminal) {
        getCommand(tr.device, tr.cmd.commandId)
          .then((cmd) => {
            if (seq !== self.trackSeq || self.tracked !== tr) return;
            tr.cmd = cmd;
            if (this.handOff(tr)) return;
            if (cmd.terminal && cmd.status === 'succeeded') {
              tr.echo = 'waiting';
              tr.echoStarted = Date.now();
            }
            this.showResult();
            this.later(seq, cmd.terminal ? 0 : POLL_MS);
          })
          .catch((err) => {
            if (seq !== self.trackSeq || self.tracked !== tr) return;
            if (err instanceof ApiError && err.status === 404) return;
            this.later(seq, POLL_MS);
          });
        return;
      }
      if (tr.cmd.status !== 'succeeded' || tr.echo !== 'waiting') return;
      getTankSettings(tr.device)
        .then((rows) => {
          if (seq !== self.trackSeq || self.tracked !== tr) return;
          const m = rows.find((x) => x.tank === tr.tank);
          tr.echo = echoState(m as unknown as Record<string, unknown>, tr.cmd.args || {}, tr.cmd.finishedAt);
          this.showResult();
          // The new report is also the form's new base: edits the device now has disappear by themselves.
          if (tr.echo !== 'waiting' && this.data.device === tr.device && this.data.tank === tr.tank) this.applyMirror(m);
          if (tr.echo === 'waiting' && Date.now() - tr.echoStarted < ECHO_WAIT_MS) this.later(seq, ECHO_MS);
          else this.showResult();
        })
        .catch(() => {
          if (seq === self.trackSeq && Date.now() - tr.echoStarted < ECHO_WAIT_MS) this.later(seq, ECHO_MS);
        });
    },

    // 找回/占用中的可能是同一罐的其他命令（如罐表 config.setTankTable，#49）：它的参数对不上储罐设置镜像，
    // 不在这里比对回显，交给操作记录跟踪。方法未知（只有 commandId）时先查到命令再判断。
    handOff(tr: Tracked): boolean {
      const self = this as unknown as EditorThis;
      if (!tr.cmd.method || tr.cmd.method === 'config.setTankSettings') return false;
      if (self.tracked === tr) {
        self.stopTrack();
        self.tracked = undefined;
        this.setData({ result: null });
      }
      wx.navigateTo({
        url: `/pages/issue-command/issue-command?device=${encodeURIComponent(tr.device)}&commandId=${encodeURIComponent(tr.cmd.commandId)}&tank=${tr.tank}`,
      });
      return true;
    },

    // Shown only on the tank it belongs to.
    showResult() {
      const self = this as unknown as EditorThis;
      const tr = self.tracked;
      if (!tr || tr.device !== this.data.device || tr.tank !== this.data.tank) {
        this.setData({ result: null });
        return;
      }
      const cmd = tr.cmd;
      const phase = cmd.status ? commandPhase(cmd.status) : 'accepted';
      let message = t(`cmdLog.progress.${phase}`);
      let tone: Result['tone'] = 'info';
      if (phase === 'succeeded') {
        if (tr.echo === 'matched') {
          message = t('cmdLog.echo.matched');
          tone = 'ok';
        } else if (tr.echo === 'mismatch') {
          message = t('cmdLog.echo.mismatch');
          tone = 'warn';
        } else {
          const timedOut = Date.now() - tr.echoStarted >= ECHO_WAIT_MS;
          message = t(timedOut ? 'cmdLog.echo.timedOut' : 'cmdLog.echo.waiting');
          tone = timedOut ? 'warn' : 'info';
        }
      } else if (phase === 'rejected' || phase === 'failed') tone = 'bad';
      else if (phase === 'expired') tone = 'warn';
      const err = commandError(cmd.status, cmd.errorCode, cmd.errorReason);
      // Succeeded but some fields untouched (e.g. older firmware): say which, never a silent "done".
      const ignored = phase === 'succeeded' ? ignoredFields(cmd) : [];
      if (ignored.length && tone === 'ok') tone = 'warn';
      this.setData({
        result: {
          tone,
          message,
          error: err ? ('key' in err ? t(err.key) : err.text) : ignored.length ? t('param.ignored', { fields: ignored.join(', ') }) : '',
          commandId: cmd.commandId,
        },
      });
    },

    onViewLog() {
      const self = this as unknown as EditorThis;
      const tr = self.tracked;
      const device = encodeURIComponent(this.data.device as string);
      const cmd = tr && tr.device === this.data.device && tr.tank === this.data.tank
        ? `&commandId=${encodeURIComponent(tr.cmd.commandId)}&tank=${tr.tank}`
        : '';
      wx.navigateTo({ url: `/pages/issue-command/issue-command?device=${device}${cmd}` });
    },
  },
});

// Instance fields (not rendered). Declared here because Component() has no typed place for them.
type EditorThis = WechatMiniprogram.Component.Instance<Record<string, unknown>, Record<string, never>, Record<string, (...a: never[]) => unknown>> & {
  loadedKey: string;
  gen: number;
  fresh: boolean;
  report: Record<string, string>;
  mirror: TankSettings | undefined;
  form: Merged;
  tracked: Tracked | undefined;
  trackSeq: number;
  timer: number;
  mediaIds: number[];
  sync(): void;
  load(): void;
  refresh(): void;
  applyMirror(m: TankSettings | undefined): void;
  stopTrack(): void;
  resumeTrack(): void;
};

export {};
