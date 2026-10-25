import { describeError } from '../../utils/errors';
import { applyTabBar, setNavTitle, t, texts } from '../../utils/i18n';
import { loadRecordsContext } from '../../utils/stationPref';
import { FOLLOW_NOTICES } from '../../utils/stationDevice';
import type { RecordType } from '../../utils/recordColumns';

type RecordItem = {
  titleKey: string;
  icon: string; // /assets/icons/{icon}.png，从设计稿 PSD 的「组59」画板抠出来的切图
  type: RecordType;
};

// Five entries, each its own list page (pages/record-list). 交班 and 库存 have no cloud data yet: their
// pages say so and send nothing (utils/recordColumns.ts SUPPORTED).
const ITEMS: RecordItem[] = [
  { titleKey: 'records.alarm', icon: 'records-alarm', type: 'alarm' },
  { titleKey: 'records.stockOut', icon: 'records-stockout', type: 'out' },
  { titleKey: 'records.stockIn', icon: 'records-stockin', type: 'in' },
  { titleKey: 'records.shift', icon: 'records-shift', type: 'shift' },
  { titleKey: 'records.inventory', icon: 'records-stock', type: 'stock' },
];

// The page follows the home page's current station and its one device (miniapp#16): there is no device
// picker. state: loading / ok / no-station (go choose one on the home page) / blocked (a station with no
// visible device, or more than one) / error (reading failed; retry).
type State = 'loading' | 'ok' | 'no-station' | 'blocked' | 'error';

Page({
  data: {
    text: texts('records'),
    items: [] as { title: string; icon: string }[],
    state: 'loading' as State,
    stationName: '',
    // Why the entries cannot be opened, or that the remembered station was replaced by another.
    notice: '',
  },

  seq: 0,

  onShow() {
    applyTabBar();
    setNavTitle('records.navTitle');
    this.setData({ text: texts('records'), items: ITEMS.map((i) => ({ title: t(i.titleKey), icon: i.icon })) });
    this.loadContext();
  },

  // Read again every time the tab is shown: the home page may have switched station, the account may
  // have changed, or the station's device may have been replaced.
  loadContext(): Promise<unknown> {
    const seq = ++this.seq;
    if (this.data.state !== 'ok') this.setData({ state: 'loading', notice: '' });
    return loadRecordsContext()
      .then((ctx) => {
        if (seq !== this.seq) return;
        if (ctx.kind === 'ok') {
          this.setData({ state: 'ok', stationName: ctx.stationName, notice: ctx.lost ? t('home.lostStation') : '' });
        } else if (ctx.kind === 'no-station') {
          this.setData({ state: 'no-station', stationName: '', notice: t('records.noStation') });
        } else {
          const notice = ctx.kind === 'ambiguous' ? t(FOLLOW_NOTICES.ambiguous) : t('records.emptyStation');
          this.setData({ state: 'blocked', stationName: ctx.stationName, notice });
        }
      })
      .catch((err: Error) => {
        if (seq !== this.seq) return;
        this.setData({ state: 'error', stationName: '', notice: describeError(err) });
      });
  },

  onItemTap(e: WechatMiniprogram.BaseEvent) {
    if (this.data.state !== 'ok') {
      wx.showToast({ title: this.data.notice || t('records.noStation'), icon: 'none', duration: 3000 });
      return;
    }
    const item = ITEMS[Number(e.currentTarget.dataset.index)];
    // No device or station on the URL: the list page decides them itself, the same way, so it can never
    // be handed a device that is no longer the station's.
    wx.navigateTo({ url: `/pages/record-list/record-list?type=${item.type}` });
  },

  onGoHome() {
    wx.switchTab({ url: '/pages/devices/devices' });
  },

  onRetry() {
    this.loadContext();
  },

  onPullDownRefresh() {
    this.loadContext().finally(() => wx.stopPullDownRefresh());
  },
});

export {};
