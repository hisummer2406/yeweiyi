// 设置页的「参数修正」「储罐设置」入口（Issue #43）：与罐详情页签用同一个编辑组件（components/param-editor）
// 和同一套草稿/提交规则，这里只决定对象：首页当前油站 → 其唯一设备 → 当前账号可见的罐。
// `?tank=` 指定罐（若可见）；只有一个罐时就是它；多罐且未指定时不猜，提示选择罐号。
import { describeError } from './errors';
import { setNavTitle, t, texts } from './i18n';
import { confirmTankSwitch, type ParamEditorRef } from './paramSwitch';
import { get } from './request';
import { FOLLOW_NOTICES } from './stationDevice';
import { loadRecordsContext } from './stationPref';
import { parseTankParam } from './tankPick';
import type { DeviceTanksResponse } from '../types/api';
import type { ParamKind } from './remoteConfig';

type State = 'loading' | 'ok' | 'no-station' | 'blocked' | 'no-tanks' | 'error';

export function paramPage(kind: ParamKind, navKey: string) {
  Page({
    data: {
      kind,
      text: texts('param'),
      form: texts('form'),
      state: 'loading' as State,
      notice: '',
      stationName: '',
      device: '',
      // 设备行的 station_id；-1 = 不在油站
      station: -2,
      tankOptions: [] as string[],
      tankIndex: -1,
      tank: -1,
      pickerShow: false,
      pickerTop: 0,
    },

    tanks: [] as number[],
    requested: undefined as number | undefined,
    seq: 0,

    onLoad(query: Record<string, string>) {
      this.requested = parseTankParam(query.tank);
    },

    onShow() {
      setNavTitle(navKey);
      this.setData({ text: texts('param'), form: texts('form') });
      this.resolve();
    },

    editor(): ParamEditorRef | null {
      return this.selectComponent('#editor') as unknown as ParamEditorRef | null;
    },

    // Another device than before (home switched station, or a replacement) starts over; the editor's drafts are
    // per device, so nothing of the old device is carried over.
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
          const station = typeof ctx.device.station_id === 'number' ? ctx.device.station_id : -1;
          this.setData({ stationName: ctx.stationName, station });
          return get<DeviceTanksResponse>(`/api/devices/${encodeURIComponent(device)}/tanks`).then((res) => {
            if (seq !== this.seq) return;
            const tanks = Array.from(new Set((res.tanks || []).map((x) => x.index))).sort((a, b) => a - b);
            this.tanks = tanks;
            if (tanks.length === 0) {
              this.setData({ state: 'no-tanks', notice: t('tankCfg.noTanks'), device, tankOptions: [], tankIndex: -1, tank: -1 });
              return;
            }
            // Same device and its tank still visible: keep it (the editor keeps the input and re-reads on show).
            const keep = device === this.data.device ? (this.data.tank as number) : -1;
            const want = keep >= 0 ? keep : this.requested;
            let index = want === undefined ? -1 : tanks.indexOf(want);
            if (index < 0 && tanks.length === 1) index = 0;
            this.requested = undefined;
            this.setData({
              state: 'ok',
              notice: '',
              device,
              tankOptions: tanks.map((n) => t('tank.no', { n })),
              tankIndex: index,
              tank: index >= 0 ? tanks[index] : -1,
            });
          });
        })
        .catch((err: Error) => {
          if (seq !== this.seq) return;
          this.setData({ state: 'error', notice: describeError(err), device: '' });
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
      const index = e.detail.index;
      this.setData({ pickerShow: false });
      if (index === this.data.tankIndex) return;
      confirmTankSwitch([this.editor()]).then((ok) => {
        if (ok) this.setData({ tankIndex: index, tank: this.tanks[index] });
      });
    },

    onClosePicker() {
      this.setData({ pickerShow: false });
    },

    onPullDownRefresh() {
      this.resolve();
      const ed = this.editor() as unknown as { refresh?: () => void } | null;
      ed?.refresh?.();
      wx.stopPullDownRefresh();
    },

    onGoHome() {
      wx.switchTab({ url: '/pages/devices/devices' });
    },

    onRetry() {
      this.resolve();
    },
  });
}
