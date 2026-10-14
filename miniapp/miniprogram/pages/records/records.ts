import { get } from '../../utils/request';
import { devicePage } from '../../utils/devicePicker';
import type { Device } from '../../types/api';

type RecordTab = 'alarms' | 'oilInOut';
type RecordItem = {
  title: string;
  icon: string; // /assets/icons/{icon}.png，从设计稿 PSD 的「组59」画板抠出来的切图
  tab?: RecordTab;
  direction?: 'IN' | 'OUT';
  reason?: string;
};

const ITEMS: RecordItem[] = [
  { title: '报警记录', icon: 'records-alarm', tab: 'alarms' },
  { title: '出库记录', icon: 'records-stockout', tab: 'oilInOut', direction: 'OUT' },
  { title: '入库记录', icon: 'records-stockin', tab: 'oilInOut', direction: 'IN' },
  { title: '交班记录', icon: 'records-shift', reason: '设备协议 V1 没有交班上行数据，非协议范围' },
  { title: '库存记录', icon: 'records-stock', reason: '全新记录类型，云端没有对应接口' },
];

Page({
  data: { items: ITEMS },

  onItemTap(e: WechatMiniprogram.BaseEvent) {
    const index = Number(e.currentTarget.dataset.index);
    const item = ITEMS[index];
    if (!item.tab) {
      wx.showToast({ title: item.reason || '暂不支持', icon: 'none', duration: 3000 });
      return;
    }
    this.pickDeviceThenOpen(item.tab, item.direction);
  },

  pickDeviceThenOpen(tab: RecordTab, direction?: 'IN' | 'OUT') {
    get<{ rows: Device[] }>('/api/devices')
      .then((res) => {
        const devices = res.rows;
        if (devices.length === 0) {
          wx.showToast({ title: '暂无可查看的设备，请联系客户管理员分配油罐', icon: 'none', duration: 3000 });
          return;
        }
        if (devices.length === 1) {
          this.openDevice(devices[0].device_name, tab, direction);
          return;
        }
        this.chooseDevice(devices.map((d) => d.device_name), 0, tab, direction);
      })
      .catch((err: Error) => wx.showToast({ title: err.message, icon: 'none' }));
  },

  // wx.showActionSheet takes at most 6 items and fails beyond that, and a customer admin or platform
  // admin can have far more devices — so a long list is paged through the sheet, 5 at a time plus a
  // "更多…" row. Dismissing the sheet also lands in `fail`; that needs no message.
  chooseDevice(names: string[], offset: number, tab: RecordTab, direction?: 'IN' | 'OUT') {
    const page = devicePage(names, offset);
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
