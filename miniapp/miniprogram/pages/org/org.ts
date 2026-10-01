const STATIONS = [
  { name: '孤岛加油站', address: '河南省郑州市金水区花园路12号' },
  { name: '胜利加油站', address: '河南省郑州市二七区大学路88号' },
];

Page({
  data: {
    stations: STATIONS,
    filteredStations: STATIONS,
    activeStation: STATIONS[0].name,
    keyword: '',
    phone: '',
  },

  onKeywordInput(e: WechatMiniprogram.Input) {
    const keyword = e.detail.value;
    const filteredStations = keyword
      ? STATIONS.filter((s) => s.name.includes(keyword) || s.address.includes(keyword))
      : STATIONS;
    this.setData({ keyword, filteredStations });
  },

  onStationTap(e: WechatMiniprogram.BaseEvent) {
    const name = e.currentTarget.dataset.name as string;
    this.setData({ activeStation: name });
    wx.showToast({ title: '云端没有"油站"这层分组，仅展示交互', icon: 'none', duration: 3000 });
  },

  onWechatInvite() {
    wx.showToast({ title: '云端没有邀请/共享设备的接口', icon: 'none', duration: 3000 });
  },

  onPhoneInput(e: WechatMiniprogram.Input) {
    this.setData({ phone: e.detail.value });
  },

  onPhoneInvite() {
    wx.showToast({ title: '云端没有邀请/共享设备的接口', icon: 'none', duration: 3000 });
  },
});

export {};
