import { ApiError, post } from './request';
import { parseDeviceCodeQr } from './pairing';
import type { BindDeviceRequest, BindDeviceResponse } from '../types/api';

// wx.scanCode → POST /api/devices/bind. Shared by the home page's empty state and the 设备添加 page
// so the pairing flow (and the QR format, see utils/pairing.ts) lives in one place.
// `onBound` runs only after the cloud has accepted the binding.
export function scanAndBind(onBound: () => void): void {
  wx.scanCode({
    onlyFromCamera: false,
    success: (res) => {
      const code = parseDeviceCodeQr(res.result);
      if (!code) {
        wx.showToast({ title: '二维码格式不正确，请联系管理员确认设备标签', icon: 'none' });
        return;
      }
      const body: BindDeviceRequest = { deviceName: code };
      post<BindDeviceResponse>('/api/devices/bind', { ...body }, true, { keepSessionOn401: true })
        .then(() => {
          wx.showToast({ title: '绑定成功', icon: 'success' });
          onBound();
        })
        .catch((err: Error) => {
          // The cloud answers 401 for every "can't resolve this" case on purpose (no such device, not
          // in your customer, unassigned, malformed), so it gets one fixed message — not the cloud's
          // "pairing token" wording, which would send the user hunting for a secret they don't have.
          const notFound = err instanceof ApiError && err.status === 401;
          wx.showToast({
            title: notFound ? '未找到该设备，请确认设备已分配给你所在的客户' : err.message,
            icon: 'none',
          });
        });
    },
    fail: () => {
      // user cancelled the scan; nothing to do
    },
  });
}
