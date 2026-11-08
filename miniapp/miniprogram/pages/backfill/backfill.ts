import { roleFromToken } from '../../utils/auth';
import { describeError } from '../../utils/errors';
import { setNavTitle, t, texts } from '../../utils/i18n';
import { backfillFingerprint, buildBackfill, newRequestId, offsetAtLocal } from '../../utils/remoteConfig';
import { addStockEvent } from '../../utils/remoteConfigApi';
import { ApiError, get } from '../../utils/request';
import { isReadOnlyRole } from '../../utils/roles';
import { FOLLOW_NOTICES } from '../../utils/stationDevice';
import { loadRecordsContext } from '../../utils/stationPref';
import { getToken } from '../../utils/storage';
import type { DeviceTanksResponse } from '../../types/api';

// 补录记录（#6 / yeweiyi-cloud#69，docs/stock-manual-entry-contract.md）：管理员手工新增一条设备漏报的出入库记录
// （断电、故障期间）。这是插入新记录，不改写设备已上报的任何记录。对象：首页当前油站 → 其唯一设备 → 可见罐。
// 每次确认一个 requestId：网络超时后「重试」复用它，云端对同一 requestId 只建一条（重放 200）。员工没有提交按钮。

type Kind = 'inbound' | 'outbound';
type Entry = { tankIndex: number; date: string; time: string; beforeLevel: string; volume: string; reason: string };
type State = 'loading' | 'ok' | 'no-station' | 'blocked' | 'no-tanks' | 'error';

// A submission whose answer never came (timeout), per device + direction, kept for this app session even after
// the dialog is dismissed or the page is left: saving the same entry again resends this body with its requestId,
// so the cloud replays instead of creating a second record (Codex review, PR #15). Cleared on success.
const pending = new Map<string, { body: Record<string, unknown>; fingerprint: string }>();

const emptyEntry = (): Entry => ({ tankIndex: -1, date: '', time: '', beforeLevel: '', volume: '', reason: '' });

function today(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

Page({
  data: {
    text: texts('backfill'),
    form: texts('form'),
    state: 'loading' as State,
    notice: '',
    stationName: '',
    device: '',
    tankOptions: [] as string[],
    inbound: emptyEntry(),
    outbound: emptyEntry(),
    maxDate: today(),
    canSubmit: false,
    submitting: '' as '' | Kind,
    pickerShow: false,
    pickerTop: 0,
    pickerValue: -1,
    pickerKind: 'inbound' as Kind,
  },

  tanks: [] as number[],
  seq: 0,

  onShow() {
    setNavTitle('backfill.navTitle');
    const token = getToken();
    this.setData({
      text: texts('backfill'),
      form: texts('form'),
      maxDate: today(),
      canSubmit: !!token && !isReadOnlyRole(roleFromToken(token)),
    });
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
        this.setData({ stationName: ctx.stationName });
        if (device === this.data.device && this.data.state === 'ok') return undefined;
        return get<DeviceTanksResponse>(`/api/devices/${encodeURIComponent(device)}/tanks`).then((res) => {
          if (seq !== this.seq) return;
          const tanks = Array.from(new Set((res.tanks || []).map((x) => x.index))).sort((a, b) => a - b);
          this.tanks = tanks;
          this.setData({
            state: tanks.length ? 'ok' : 'no-tanks',
            notice: tanks.length ? '' : t('tankCfg.noTanks'),
            device,
            tankOptions: tanks.map((n) => t('tank.no', { n })),
            inbound: emptyEntry(),
            outbound: emptyEntry(),
          });
        });
      })
      .catch((err: Error) => {
        if (seq !== this.seq) return;
        this.setData({ state: 'error', notice: describeError(err), device: '' });
      });
  },

  onOpenTank(e: WechatMiniprogram.BaseEvent) {
    const kind = e.currentTarget.dataset.kind as Kind;
    wx.createSelectorQuery()
      .select(`#row-${kind}-tank`)
      .boundingClientRect((rect) => {
        if (rect) {
          this.setData({ pickerShow: true, pickerTop: rect.bottom, pickerKind: kind, pickerValue: this.data[kind].tankIndex });
        }
      })
      .exec();
  },
  onPickTank(e: WechatMiniprogram.CustomEvent<{ index: number }>) {
    this.setData({ [`${this.data.pickerKind}.tankIndex`]: e.detail.index, pickerShow: false });
  },
  onClosePicker() {
    this.setData({ pickerShow: false });
  },
  // One handler per field: data-kind (inbound/outbound) + data-field
  onField(e: WechatMiniprogram.Input | WechatMiniprogram.PickerChange) {
    const { kind, field } = e.currentTarget.dataset as { kind: Kind; field: keyof Entry };
    this.setData({ [`${kind}.${field}`]: (e.detail as { value: string }).value });
  },

  onPreview(e: WechatMiniprogram.BaseEvent) {
    const kind = e.currentTarget.dataset.kind as Kind;
    const entry = this.data[kind] as Entry;
    const notSelected = t('backfill.notSelected');
    wx.showModal({
      title: t(kind === 'inbound' ? 'backfill.previewTitleIn' : 'backfill.previewTitleOut'),
      content: t('backfill.previewBody', {
        tank: this.data.tankOptions[entry.tankIndex] || notSelected,
        time: entry.date ? `${entry.date} ${entry.time || ''}`.trim() : notSelected,
        level: entry.beforeLevel || '0',
        volume: entry.volume || '0',
      }),
      showCancel: false,
    });
  },

  onSave(e: WechatMiniprogram.BaseEvent) {
    const kind = e.currentTarget.dataset.kind as Kind;
    if (!this.data.canSubmit || this.data.state !== 'ok' || this.data.submitting) return;
    const entry = this.data[kind] as Entry;
    const tank = entry.tankIndex >= 0 ? this.tanks[entry.tankIndex] : undefined;
    const device = this.data.device as string;
    const key = `${device}/${kind}`;
    const fingerprint = backfillFingerprint(entry.tankIndex, entry, entry.reason);
    const unresolved = pending.get(key);
    if (unresolved && unresolved.fingerprint === fingerprint) {
      // Same entry as the unanswered one: resend it as it was (same requestId)
      this.submit(kind, device, unresolved.body, key, fingerprint);
      return;
    }
    const requestId = newRequestId('mpbf');
    const offset = offsetAtLocal(entry.date, entry.time);
    const built = buildBackfill(
      kind === 'inbound' ? 'IN' : 'OUT',
      tank,
      entry,
      entry.reason,
      requestId,
      Date.now(),
      offset === null ? new Date().getTimezoneOffset() : offset,
    );
    if (!built.body) {
      wx.showToast({ title: t(`backfill.bad.${built.problem}`), icon: 'none', duration: 3000 });
      return;
    }
    const body = built.body;
    wx.showModal({
      title: t(kind === 'inbound' ? 'backfill.confirmIn' : 'backfill.confirmOut'),
      content: t('backfill.confirmBody', {
        device,
        tank: this.data.tankOptions[entry.tankIndex],
        time: `${entry.date} ${entry.time}`,
        level: String(body.beforeLevelMm),
        volume: String(body.volumeL),
      }),
      success: (r) => {
        if (r.confirm) this.submit(kind, device, body as unknown as Record<string, unknown>, key, fingerprint);
      },
    });
  },

  // Same body (same requestId) on retry: the cloud keeps one record.
  submit(kind: Kind, device: string, body: Record<string, unknown>, key: string, fingerprint: string) {
    this.setData({ submitting: kind });
    addStockEvent(device, body)
      .then(() => {
        pending.delete(key);
        this.setData({ submitting: '', [kind]: emptyEntry() });
        wx.showToast({ title: t('backfill.done'), icon: 'none', duration: 3000 });
      })
      .catch((err: unknown) => {
        this.setData({ submitting: '' });
        const status = err instanceof ApiError ? err.status : 0;
        const code = err instanceof ApiError ? (err.body as { code?: string } | undefined)?.code : undefined;
        if (status === 0) {
          pending.set(key, { body, fingerprint });
          wx.showModal({
            title: t('backfill.unconfirmedTitle'),
            content: t('backfill.unconfirmedBody'),
            confirmText: t('cfgSend.retrySame'),
            cancelText: t('cfgSend.later'),
            success: (r) => {
              if (r.confirm) this.submit(kind, device, body, key, fingerprint);
            },
          });
          return;
        }
        const known = ['time_before_visible', 'time_in_future', 'tank_invalid', 'level_invalid', 'volume_invalid'];
        const text = code && known.includes(code) ? t(`backfill.err.${code}`) : describeError(err);
        wx.showModal({ title: t('cfgSend.failTitle'), content: text, showCancel: false });
      });
  },

  onGoHome() {
    wx.switchTab({ url: '/pages/devices/devices' });
  },
  onRetry() {
    this.resolve();
  },
});

export {};
