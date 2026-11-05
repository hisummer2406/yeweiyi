import { setNavTitle, texts } from '../../utils/i18n';
import { scanAndLocate } from '../../utils/scan';

Page({
  data: { text: texts('deviceAdd') },

  onShow() {
    setNavTitle('deviceAdd.navTitle');
  },

  onScan() {
    // Scan only locates the device; open its detail page in place of this one. What the user sees
    // there is filtered by the cloud to their granted tanks.
    scanAndLocate((deviceName) => {
      wx.redirectTo({ url: `/pages/device-detail/device-detail?device=${encodeURIComponent(deviceName)}` });
    });
  },
});
