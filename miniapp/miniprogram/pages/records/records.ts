import { describeError } from '../../utils/errors';
import { applyTabBar, setNavTitle, t, texts } from '../../utils/i18n';
import { get } from '../../utils/request';
import { devicePage } from '../../utils/devicePicker';
import { showComingSoon } from '../../utils/comingSoon';
import type { Device } from '../../types/api';

type RecordTab = 'alarms' | 'oilInOut';
type RecordItem = {
  titleKey: string;
  icon: string; // /assets/icons/{icon}.png，从设计稿 PSD 的「组59」画板抠出来的切图
  tab?: RecordTab;
  direction?: 'IN' | 'OUT';
};

// Shift handover has no uplink in device protocol V1, and stock records are a new record type the cloud
// has no endpoint for: those two only answer "coming soon" (the reason is a developer note, never shown).
const ITEMS: RecordItem[] = [
  { titleKey: 'records.alarm', icon: 'records-alarm', tab: 'alarms' },
  { titleKey: 'records.stockOut', icon: 'records-stockout', tab: 'oilInOut', direction: 'OUT' },
  { titleKey: 'records.stockIn', icon: 'records-stockin', tab: 'oilInOut', direction: 'IN' },
  { titleKey: 'records.shift', icon: 'records-shift' },
  { titleKey: 'records.inventory', icon: 'records-stock' },
];

Page({
  data: { items: [] as { title: string; icon: string }[] },

  onShow() {
    applyTabBar();
    setNavTitle('records.navTitle');
    this.setData({ items: ITEMS.map((i) => ({ title: t(i.titleKey), icon: i.icon })) });
  },

  onItemTap(e: WechatMiniprogram.BaseEvent) {
    const index = Number(e.currentTarget.dataset.index);
    const item = ITEMS[index];
    if (!item.tab) {
      showComingSoon();
      return;
    }
    this.pickDeviceThenOpen(item.tab, item.direction);
  },

  pickDeviceThenOpen(tab: RecordTab, direction?: 'IN' | 'OUT') {
    get<{ rows: Device[] }>('/api/devices')
      .then((res) => {
        const devices = res.rows;
        if (devices.length === 0) {
          wx.showToast({ title: t('records.noDevices'), icon: 'none', duration: 3000 });
          return;
        }
        if (devices.length === 1) {
          this.openDevice(devices[0].device_name, tab, direction);
          return;
        }
        this.chooseDevice(devices.map((d) => d.device_name), 0, tab, direction);
      })
      .catch((err: Error) => wx.showToast({ title: describeError(err), icon: 'none' }));
  },

  // wx.showActionSheet takes at most 6 items and fails beyond that, and a customer admin or platform
  // admin can have far more devices — so a long list is paged through the sheet, 5 at a time plus a
  // "more…" row. Dismissing the sheet also lands in `fail`; that needs no message.
  chooseDevice(names: string[], offset: number, tab: RecordTab, direction?: 'IN' | 'OUT') {
    const page = devicePage(names, offset, t('common.more'));
    wx.showActionSheet({
      itemList: page.items,
      success: (r) => {
        if (r.tapIndex < page.devices.length) {
          this.openDevice(page.devices[r.tapIndex], tab, direction);
        } else {
          this.chooseDevice(names, page.nextOffset, tab, direction);
        }
      },
    });
  },

  openDevice(device: string, tab: RecordTab, direction?: 'IN' | 'OUT') {
    const dirQuery = direction ? `&direction=${direction}` : '';
    wx.navigateTo({
      url: `/pages/device-detail/device-detail?device=${encodeURIComponent(device)}&tab=${tab}${dirQuery}`,
    });
  },
});

export {};
