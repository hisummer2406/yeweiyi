import { setNavTitle, texts } from '../../utils/i18n';
import { filterStations, type StationOption } from '../../utils/stations';

// Opened from the home page (pages/devices onPickStation). The home page hands over the options and the
// current key through the opener event channel and listens for `selected`; this page owns no data
// loading, so there is nothing to fail here.
Page({
  data: {
    text: texts('stationSelect'),
    query: '',
    all: [] as StationOption[],
    shown: [] as StationOption[],
    current: '',
  },

  onShow() {
    setNavTitle('stationSelect.navTitle');
  },

  onLoad() {
    this.getOpenerEventChannel().on('init', (payload: { options: StationOption[]; current: string }) => {
      this.setData({ all: payload.options, shown: payload.options, current: payload.current });
    });
  },

  onInput(e: WechatMiniprogram.CustomEvent) {
    const query = e.detail.value as string;
    this.setData({ query, shown: filterStations(this.data.all, query) });
  },

  onPick(e: WechatMiniprogram.BaseEvent) {
    const key = e.currentTarget.dataset.key as string;
    this.getOpenerEventChannel().emit('selected', key);
    wx.navigateBack();
  },
});
