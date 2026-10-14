import { ApiError, post } from './request';
import { describeResolveFailure, parseDeviceCodeQr } from './pairing';
import type { ResolveDeviceRequest, ResolveDeviceResponse } from '../types/api';

// POST /api/devices/resolve (yeweiyi-cloud tank-grants-contract v1.0 §3), shared by the scan below and
// the mini-program-code entry (device-detail `scene`). It only LOCATES a device: nothing is written,
// nobody is granted anything, and repeating it changes nothing. What the user may see on the device
// page is decided by the cloud from their tank grants, not by this call. Resolves to the ACTUAL device
// name the cloud answered with — never the scanned text.
// No keepSessionOn401: resolve answers 404 (not 401) for "cannot locate", and 410 bind_removed is not a
// session problem either (request.ts ends the session only on 401 / bare 403 "not authorized").
export function resolveDevice(code: string): Promise<string> {
  const body: ResolveDeviceRequest = { code };
  return post<ResolveDeviceResponse>('/api/devices/resolve', { ...body }).then((r) => {
    if (!r || typeof r.deviceName !== 'string' || !r.deviceName) {
      throw new Error('设备定位的响应格式不符合预期，请联系管理员');
    }
    return r.deviceName;
  });
}

export function describeResolveError(err: Error): string {
  const e = err instanceof ApiError ? err : undefined;
  const code = e && e.body && typeof (e.body as { code?: unknown }).code === 'string'
    ? (e.body as { code: string }).code
    : '';
  return describeResolveFailure(e ? e.status : -1, code, err.message);
}

// wx.scanCode → resolveDevice. Shared by the home page and the 设备添加 page.
// `onResolved` receives the actual device name.
export function scanAndLocate(onResolved: (deviceName: string) => void): void {
  wx.scanCode({
    onlyFromCamera: false,
    success: (res) => {
      const code = parseDeviceCodeQr(res.result);
      if (!code) {
        wx.showToast({ title: '二维码格式不正确，请联系管理员确认设备标签', icon: 'none' });
        return;
      }
      resolveDevice(code)
        .then(onResolved)
        .catch((err: Error) => {
          wx.showToast({ title: describeResolveError(err), icon: 'none', duration: 3000 });
        });
    },
    fail: () => {
      // user cancelled the scan; nothing to do
    },
  });
}
