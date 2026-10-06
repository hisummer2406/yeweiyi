import { post } from './request';
import { parseDevicePairingQr } from './pairing';

// wx.scanCode → POST /api/devices/bind. Shared by the home page's empty state and the 设备添加 page
// so the pairing flow (and its placeholder QR format, see utils/pairing.ts) lives in one place.
// `onBound` runs only after the cloud has accepted the binding.
export function scanAndBind(onBound: () => void): void {
  wx.scanCode({
    onlyFromCamera: false,
    success: (res) => {
      const pairing = parseDevicePairingQr(res.result);
      if (!pairing) {
        wx.showToast({ title: '二维码格式不正确，请联系管理员确认设备标签', icon: 'none' });
        return;
      }
      post('/api/devices/bind', { deviceName: pairing.deviceName, pairingToken: pairing.pairingToken })
        .then(() => {
          wx.showToast({ title: '绑定成功', icon: 'success' });
          onBound();
        })
        .catch((err: Error) => {
          wx.showToast({ title: err.message, icon: 'none' });
        });
    },
    fail: () => {
      // user cancelled the scan; nothing to do
    },
  });
}
