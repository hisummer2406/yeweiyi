import { describeError } from '../../utils/errors';
import { formatLocalTime } from '../../utils/format';
import { applyTabBar, currentLocale, t, texts } from '../../utils/i18n';
import { onNetworkBack } from '../../utils/network';
import { ApiError, get } from '../../utils/request';
import { getToken } from '../../utils/storage';
import { scanAndLocate } from '../../utils/scan';
import { showComingSoon } from '../../utils/comingSoon';
import { toTankViews, type TankView } from '../../utils/telemetry';
import { telemetryFailure } from '../../utils/failureState';
import { userKeyFromToken } from '../../utils/auth';
import { getRememberedStation, rememberStation } from '../../utils/stationPref';
import { stationIdFromKey, stationParam } from '../../utils/stationDevice';
import {
  buildStationOptions,
  devicesOfStation,
  pickStation,
  type StationOption,
} from '../../utils/stations';
import type { Device, LatestTelemetryResponse, Station } from '../../types/api';

// One device's slice of the home page. `state` is per device on purpose: one device failing (or
// never having reported) must not blank the others.
type GroupState = 'loading' | 'ok' | 'never' | 'error';

type DeviceGroup = {
  deviceName: string;
  state: GroupState;
  ts: string;
  // Phone clock at the last successful read of this device; '' = never read in this run.
  fetchedAt: string;
  tanks: TankView[];
  error: string;
  // With state 'never': which empty state — never reported, past the retention period, or not set up
  // on the platform yet (yeweiyi-cloud#79). '' falls back to the plain "never reported" text.
  note: string;
  // Set when the last refresh failed but an earlier one succeeded: the tanks on screen are kept and the
  // card says they may be out of date, and since when (the phone's clock at the last success).
  staleText: string;
};

const emptyGroup = (deviceName: string): DeviceGroup => ({
  deviceName,
  state: 'loading',
  ts: '',
  fetchedAt: '',
  tanks: [],
  error: '',
  note: '',
  staleText: '',
});

const nowText = () => formatLocalTime(new Date().toISOString(), currentLocale());

Page({
  data: {
    text: texts('home'),
    homeLanguage: currentLocale(),
    groups: [] as DeviceGroup[],
    // Distinguishes "still asking which devices I have" from "I have none", so the first-run empty
    // state does not flash while /api/devices is in flight.
    devicesLoaded: false,
    listError: '',
    // Station dropdown. hasStations is false when the cloud has no stations for this user, in which
    // case the home page is the flat list it was before stations existed.
    hasStations: false,
    stationName: '',
    stationKey: '',
    // All devices this user can see, regardless of the selected station. Distinguishes "no devices at
    // all" (first-run empty state: go scan) from "the selected station has none".
    deviceTotal: 0,
    // Shown when the station the user last looked at has no device they can see any more — typically
    // after the back office replaced its screen and the new one is not assigned to them yet.
    stationNotice: '',
    showSingleDeviceHeader: false,
    // The header is drawn under the status bar (navigationStyle: custom), so it needs the status
    // bar's height in rpx. Falls back to the design draft's 88rpx if the API is unavailable.
    statusBarRpx: 88,
    // 每个罐占首页可用高度的四分之一；测量前沿用设计稿行高。
    tankRowHeightRpx: 293,
    compactHome: false,
  },

  // Bumped on every reload; a telemetry response from an older reload is dropped instead of
  // overwriting a newer one (pull-to-refresh while a slow request is still out).
  loadSeq: 0,
  allDevices: [] as Device[],
  allStations: [] as Station[],
  stationOptions: [] as StationOption[],
  // The station the user picked on the select page during this page's lifetime. Kept in memory so the
  // choice survives even if the best-effort storage write failed. Logout / account switch goes through
  // reLaunch, which destroys the page, so this cannot leak between accounts.
  chosenStationKey: undefined as string | undefined,

  // Stops listening for the network coming back.
  stopNet: null as (() => void) | null,

  onLoad() {
    // Network back (airplane mode off, wifi to mobile data ...): read again at once instead of waiting for
    // the user to pull down. onShow only fires when the page comes into view.
    this.stopNet = onNetworkBack(() => {
      if (getToken()) this.loadHome();
    });
    try {
      const info = wx.getWindowInfo();
      this.setData({ statusBarRpx: Math.round((info.statusBarHeight * 750) / info.windowWidth) });
    } catch {
      // keep the default
    }
  },

  onReady() {
    this.measureHomeLayout();
  },

  onResize() {
    try {
      const info = wx.getWindowInfo();
      this.setData(
        { statusBarRpx: Math.round((info.statusBarHeight * 750) / info.windowWidth) },
        () => this.measureHomeLayout(),
      );
    } catch {
      // 无窗口信息时保留当前排版。
    }
  },

  // windowHeight 已是小程序可用窗口，不再重复扣除微信原生 tabBar / 底部安全区。
  // 只量头部和第一组上报时间的高度，滚动后刷新也不会因坐标变化把罐行越算越高。
  measureHomeLayout() {
    wx.createSelectorQuery()
      .select('.home-header').boundingClientRect()
      .select('.group-head').boundingClientRect()
      .exec((rects) => {
        const [header, group] = rects as (WechatMiniprogram.BoundingClientRectResult | null)[];
        if (!header) return;
        try {
          const info = wx.getWindowInfo();
          const available = info.windowHeight - header.height - (group?.height || 0);
          if (available <= 0 || info.windowWidth <= 0) return;
          // 向下取两位小数，避免四行累计舍入误差把第四罐推入底部导航。
          const tankRowHeightRpx = Math.floor((available * 750 * 100) / info.windowWidth / 4) / 100;
          const compactHome = tankRowHeightRpx < 274;
          if (tankRowHeightRpx !== this.data.tankRowHeightRpx || compactHome !== this.data.compactHome) {
            this.setData({ tankRowHeightRpx, compactHome });
          }
        } catch {
          // 旧运行环境无法测量时保留设计稿尺寸，全部罐仍可自然滚动查看。
        }
      });
  },

  onShow() {
    if (!getToken()) {
      wx.reLaunch({ url: '/pages/login/login' });
      return;
    }
    applyTabBar();
    this.setData({ text: texts('home'), homeLanguage: currentLocale() });
    this.loadHome();
  },

  onUnload() {
    this.stopNet?.();
    this.stopNet = null;
  },

  onPullDownRefresh() {
    this.loadHome().finally(() => wx.stopPullDownRefresh());
  },

  // GET /api/devices answers the devices this user may see, GET /api/stations the stations they sit
  // in (yeweiyi-cloud#53). Stations are an enhancement: an older cloud answers 404, and any failure
  // here just means "no dropdown" — it must never break the home page. Then one telemetry request per
  // device of the SELECTED station, in parallel — there is no batch endpoint.
  loadHome(): Promise<unknown> {
    const seq = ++this.loadSeq;
    return Promise.all([
      get<{ rows: Device[] }>('/api/devices'),
      get<{ rows: Station[] }>('/api/stations').catch(() => ({ rows: [] as Station[] })),
    ])
      .then(([devRes, stRes]) => {
        if (seq !== this.loadSeq) return;
        this.allDevices = devRes.rows;
        this.allStations = stRes.rows;
        this.stationOptions = buildStationOptions(stRes.rows, devRes.rows, t('station.ungrouped'));
        return this.showStation(seq, this.chosenStationKey || getRememberedStation(this.currentUserKey()));
      })
      .catch((err: Error) => {
        if (seq !== this.loadSeq) return;
        const message = describeError(err);
        this.setData({ devicesLoaded: true, listError: message });
        wx.showToast({ title: message, icon: 'none' });
      });
  },

  // The station preference is kept per account, keyed by user id (not phone or username, which a
  // customer-side account does not have as a stable name).
  currentUserKey(): string {
    return userKeyFromToken(getToken());
  },

  // Show one station's devices (or every device when there is no dropdown) and load their telemetry.
  showStation(seq: number, preferredKey?: string): Promise<unknown> {
    const options = this.stationOptions as StationOption[];
    const current = pickStation(options, preferredKey);
    // A remembered station that is no longer offered: its device was replaced, or never reached this
    // user. Say so instead of silently landing elsewhere. Nothing here can authorize the new device.
    const lostStation =
      typeof stationIdFromKey(preferredKey) === 'number' && !options.some((o) => o.key === preferredKey);
    const visible = current ? devicesOfStation(this.allDevices, this.allStations, current.key) : this.allDevices;
    // A device that was already on screen keeps its last good readings while it is read again, so a failed
    // refresh can show them as out of date instead of showing nothing.
    const before = new Map((this.data.groups as DeviceGroup[]).map((g) => [g.deviceName, g]));
    const groups = visible.map((d: Device) => {
      const prev = before.get(d.device_name);
      return prev && prev.state === 'ok' && prev.tanks.length > 0 ? { ...prev, staleText: '' } : emptyGroup(d.device_name);
    });
    this.setData({
      groups,
      devicesLoaded: true,
      listError: '',
      showSingleDeviceHeader: groups.length > 1,
      hasStations: options.length > 0,
      stationName: current ? current.name : '',
      stationKey: current ? current.key : '',
      deviceTotal: this.allDevices.length,
      stationNotice: lostStation ? t('home.lostStation') : '',
    }, () => this.measureHomeLayout());
    return Promise.all(groups.map((g: DeviceGroup) => this.loadDevice(g.deviceName, seq)));
  },

  onPickStation() {
    wx.navigateTo({
      url: '/pages/station-select/station-select',
      events: {
        selected: (key: string) => this.onStationSelected(key),
      },
      success: (res) => {
        res.eventChannel.emit('init', { options: this.stationOptions, current: this.data.stationKey });
      },
    });
  },

  onStationSelected(key: string) {
    // Only record the choice. The select page calls wx.navigateBack() right after emitting, which
    // always fires onShow -> loadHome(); reloading here too would load everything twice.
    this.chosenStationKey = key;
    rememberStation(this.currentUserKey(), key);
  },

  loadDevice(deviceName: string, seq: number): Promise<void> {
    return get<LatestTelemetryResponse>(
      `/api/devices/${encodeURIComponent(deviceName)}/telemetry/latest`,
    )
      .then((res) =>
        this.patchGroup(deviceName, seq, {
          state: 'ok',
          ts: formatLocalTime(res.ts, currentLocale()),
          tanks: toTankViews(res, deviceName),
          error: '',
          staleText: '',
          fetchedAt: nowText(),
        }),
      )
      .catch((err: ApiError) => {
        const empty = telemetryFailure(err.status, err.body);
        if (empty.kind === 'empty') {
          // A real empty state, not an error: never reported, past the retention period, or not set up on
          // the platform yet — each says so (yeweiyi-cloud#79), none shows the previous readings.
          const note = t(empty.key, {
            time: empty.lastReportedAt ? formatLocalTime(empty.lastReportedAt, currentLocale()) : '—',
          });
          this.patchGroup(deviceName, seq, { state: 'never', ts: '', tanks: [], error: '', note, staleText: '' });
        } else {
          const shown = (this.data.groups as DeviceGroup[]).find((g) => g.deviceName === deviceName);
          if (shown && shown.fetchedAt && shown.tanks.length > 0) {
            // Keep what was shown and say how old it is, rather than replacing readings with an error.
            this.patchGroup(deviceName, seq, { state: 'ok', staleText: t('home.stale', { time: shown.fetchedAt }) });
          } else {
            this.patchGroup(deviceName, seq, { state: 'error', ts: '', tanks: [], error: describeError(err), staleText: '' });
          }
        }
      });
  },

  patchGroup(deviceName: string, seq: number, patch: Partial<DeviceGroup>) {
    if (seq !== this.loadSeq) return;
    const index = this.data.groups.findIndex((g: DeviceGroup) => g.deviceName === deviceName);
    if (index < 0) return;
    const update: Record<string, unknown> = {};
    for (const key of Object.keys(patch) as (keyof DeviceGroup)[]) {
      update[`groups[${index}].${key}`] = patch[key];
    }
    this.setData(update, () => this.measureHomeLayout());
  },

  onRetryDevice(e: WechatMiniprogram.BaseEvent) {
    const deviceName = e.currentTarget.dataset.device as string;
    this.patchGroup(deviceName, this.loadSeq, { state: 'loading', error: '' });
    this.loadDevice(deviceName, this.loadSeq);
  },

  onTapTank(e: WechatMiniprogram.BaseEvent) {
    const device = e.currentTarget.dataset.device as string;
    // The protocol tank number (not the row's position): the detail page opens on this tank.
    const tank = Number(e.currentTarget.dataset.tank);
    const tankQuery = Number.isInteger(tank) ? `&tank=${tank}` : '';
    // The station the card was shown under travels with the device, so the detail page can follow the
    // station if its device is replaced while the page is open (see device-detail refreshAssociation).
    const station = stationParam(stationIdFromKey(this.data.stationKey));
    const stationQuery = station ? `&station=${station}` : '';
    wx.navigateTo({
      url: `/pages/device-detail/device-detail?device=${encodeURIComponent(device)}${tankQuery}${stationQuery}`,
    });
  },

  onScanDevice() {
    // Locates only: nothing is bound or granted. The detail page shows the granted tanks.
    scanAndLocate((deviceName) => {
      wx.navigateTo({ url: `/pages/device-detail/device-detail?device=${encodeURIComponent(deviceName)}` });
    });
  },

  // 设计稿「首页-添加」：+ 号菜单（扫码只定位设备，不创建绑定或授权）。邀请加入本阶段不做（账号只在后台创建），油站管理暂未开放。
  onMoreActions() {
    wx.showActionSheet({
      itemList: [t('home.scan'), t('home.stationMgmt')],
      success: (res) => {
        if (res.tapIndex === 0) {
          wx.navigateTo({ url: '/pages/device-add/device-add' });
        } else {
          showComingSoon();
        }
      },
    });
  },
});
