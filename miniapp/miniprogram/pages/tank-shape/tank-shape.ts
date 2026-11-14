// 罐表设置首页：选罐型进入尺寸页生成/下发；下方「设备当前罐表」（#41）显示首页当前油站设备各罐
// 实际在用的罐表——云端标定镜像（GET .../tanks/:tank/calibration，设备上报，云端只读），未上报给出明确提示。
// 员工只看到被授权的罐（/tanks 已按授权过滤）。
import { formatLocalTime } from '../../utils/format';
import { currentLocale, setNavTitle, t, texts } from '../../utils/i18n';
import { codeOfBody } from '../../utils/loginFlow';
import { getTankCalibration } from '../../utils/remoteConfigApi';
import { ApiError, get } from '../../utils/request';
import { FOLLOW_NOTICES } from '../../utils/stationDevice';
import { loadRecordsContext } from '../../utils/stationPref';
import { deviceTableView } from '../../utils/tankTable';
import { describeError } from '../../utils/errors';
import type { DeviceTanksResponse } from '../../types/api';

const SHAPE_KEYS = ['horizontal', 'vertical', 'cuboid'];

Page({
  data: {
    text: texts('tankShape'),
    edit: texts('tankEdit'),
    shapes: SHAPE_KEYS.map((key) => ({ key, label: '' })),
    // 设备当前罐表
    devNotice: '', // 读不到设备/罐时的整块提示
    devLoading: true,
    tanks: [] as { n: number; label: string }[],
    tank: 0,
    tableLoading: false,
    tableNotice: '',
    summary: '',
    reportedAt: '',
    rows: [] as { heightMm: number; volumeL: number }[],
    sampled: false,
  },

  device: '',
  // 丢弃迟到的应答：设备列表一代、罐表一代
  devSeq: 0,
  tableSeq: 0,

  onShow() {
    setNavTitle('tankShape.navTitle');
    this.setData({
      text: texts('tankShape'),
      edit: texts('tankEdit'),
      shapes: SHAPE_KEYS.map((key) => ({ key, label: t(`tankShape.${key}`) })),
    });
    this.loadDevice();
  },

  onShapeTap(e: WechatMiniprogram.BaseEvent) {
    const key = e.currentTarget.dataset.key as string;
    wx.navigateTo({ url: `/pages/tank-shape-edit/tank-shape-edit?shape=${key}` });
  },

  loadDevice() {
    const seq = ++this.devSeq;
    this.setData({ devLoading: true, devNotice: '' });
    loadRecordsContext()
      .then((ctx) => {
        if (ctx.kind !== 'ok') {
          const key = ctx.kind === 'no-station' ? 'tankShape.noStation' : ctx.kind === 'ambiguous' ? FOLLOW_NOTICES.ambiguous : 'records.emptyStation';
          throw new Error(t(key));
        }
        const device = ctx.device.device_name;
        return get<DeviceTanksResponse>(`/api/devices/${encodeURIComponent(device)}/tanks`).then((res) => {
          if (seq !== this.devSeq) return;
          const tanks = Array.from(new Set((res.tanks || []).map((x) => x.index))).sort((a, b) => a - b);
          if (tanks.length === 0) throw new Error(t('tankEdit.noTanks'));
          const keep = device === this.device && tanks.includes(this.data.tank as number);
          this.device = device;
          const tank = keep ? (this.data.tank as number) : tanks[0];
          this.setData({ devLoading: false, tanks: tanks.map((n) => ({ n, label: t('tank.no', { n }) })), tank });
          this.loadTable(tank);
        });
      })
      .catch((err: Error) => {
        if (seq !== this.devSeq) return;
        this.device = '';
        this.setData({ devLoading: false, devNotice: err instanceof ApiError ? describeError(err) : err.message || describeError(err), tanks: [] });
      });
  },

  onTankTap(e: WechatMiniprogram.BaseEvent) {
    const tank = Number(e.currentTarget.dataset.n);
    if (!tank || tank === this.data.tank) return;
    this.setData({ tank });
    this.loadTable(tank);
  },

  loadTable(tank: number) {
    const seq = ++this.tableSeq;
    const device = this.device;
    this.setData({ tableLoading: true, tableNotice: '', summary: '', reportedAt: '', rows: [], sampled: false });
    getTankCalibration(device, tank)
      .then((cal) => {
        if (seq !== this.tableSeq) return;
        const view = deviceTableView(cal);
        if (!view) {
          this.setData({ tableLoading: false, tableNotice: t('tankShape.notReported') });
          return;
        }
        this.setData({
          tableLoading: false,
          summary: t('tankEdit.summary', { n: String(view.pointCount), height: String(view.maxHeightMm), volume: String(view.maxVolumeL) }),
          reportedAt: t('tankShape.reportedAt', { time: formatLocalTime(cal.reportedAt, currentLocale()) }),
          rows: view.rows,
          sampled: view.sampled,
        });
      })
      .catch((err) => {
        if (seq !== this.tableSeq) return;
        const notReported = err instanceof ApiError && err.status === 404 && codeOfBody(err.body) === 'calibration_not_reported';
        this.setData({ tableLoading: false, tableNotice: notReported ? t('tankShape.notReported') : describeError(err) });
      });
  },

  onRetry() {
    if (this.device && this.data.tank) this.loadTable(this.data.tank as number);
    else this.loadDevice();
  },
});

export {};
