import { ApiError, post } from './request';
import { describeResolveFailure, parseDeviceCodeQr } from './pairing';
import type { ResolveDeviceRequest, ResolveDeviceResponse } from '../types/api';

// wx.scanCode → POST /api/devices/resolve (yeweiyi-cloud tank-grants-contract v1.0 §3). Shared by the
// home page and the 设备添加 page. The scan only LOCATES a device: nothing is written, nobody is
// granted anything, and scanning the same label again changes nothing. What the user may see on the
// device page is decided by the cloud from their tank grants, not by this call.
// `onResolved` receives the ACTUAL device name the cloud answered with — never the scanned text.
export function scanAndLocate(onResolved: (deviceName: string) => void): void {
  wx.scanCode({
    onlyFromCamera: false,
    success: (res) => {
      const code = parseDeviceCodeQr(res.result);
      if (!code) {
        wx.showToast({ title: '二维码格式不正确，请联系管理员确认设备标签', icon: 'none' });
        return;
      }
      const body: ResolveDeviceRequest = { code };
      // No keepSessionOn401: unlike the retired bind, resolve answers 404 (not 401) for "cannot
      // locate", so a 401 here is a real dead token and should end the session as everywhere else.
      post<ResolveDeviceResponse>('/api/devices/resolve', { ...body })
        .then((r) => {
          if (!r || typeof r.deviceName !== 'string' || !r.deviceName) {
            throw new Error('设备定位的响应格式不符合预期，请联系管理员');
          }
          onResolved(r.deviceName);
        })
        .catch((err: Error) => {
          const e = err instanceof ApiError ? err : undefined;
          const code2 = e && e.body && typeof (e.body as { code?: unknown }).code === 'string'
            ? ((e.body as { code: string }).code)
            : '';
          wx.showToast({
            title: describeResolveFailure(e ? e.status : -1, code2, err.message),
            icon: 'none',
            duration: 3000,
          });
        });
    },
    fail: () => {
      // user cancelled the scan; nothing to do
    },
  });
}
