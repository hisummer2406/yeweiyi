import { scanAndBind } from '../../utils/bind';

Page({
  onScan() {
    // Back to the home page, whose onShow reloads the device list and picks up the new device.
    scanAndBind(() => wx.navigateBack());
  },
});
