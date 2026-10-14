import { scanAndLocate } from '../../utils/scan';

Page({
  onScan() {
    // Scan only locates the device; open its detail page in place of this one. What the user sees
    // there is filtered by the cloud to their granted tanks.
    scanAndLocate((deviceName) => {
      wx.redirectTo({ url: `/pages/device-detail/device-detail?device=${encodeURIComponent(deviceName)}` });
    });
  },
});
