import { request } from '@umijs/max';
import { toApiError } from './apiError';

/** GET /api/devices：平台管理员看到全部业务登记设备，响应 {rows}（internal/api/devices.go）。 */
export async function listDevices(): Promise<API.DeviceRow[]> {
  const res = await request<{ rows: API.DeviceRow[] }>('/api/devices', {
    method: 'GET',
  });
  return res.rows;
}

export type RegisterDeviceOutcome = {
  /** created：首次登记（201）；exists：同一客户重复登记，返回已有结果（200） */
  kind: 'created' | 'exists';
  deviceName: string;
  customerId: number;
};

/**
 * POST /api/devices：平台管理员幂等登记设备并指定客户。不触碰任何配对密钥，
 * 也不要用 pairing-secret 接口代替（它每次调用都会轮换密钥）。
 * 其他客户已拥有返回 409，客户不存在或编号含 / ? # \ 返回 400，非平台管理员 403；失败抛 ApiError。
 * 提示由调用方按状态码给出，所以跳过全局错误提示（401 在 toApiError 里转交）。
 */
export async function registerDevice(
  params: API.RegisterDeviceParams,
): Promise<RegisterDeviceOutcome> {
  try {
    const { data, status } = await request<API.RegisterDeviceResult>(
      '/api/devices',
      {
        method: 'POST',
        data: params,
        getResponse: true,
        skipErrorHandler: true,
      },
    );
    return {
      kind: status === 201 ? 'created' : 'exists',
      deviceName: data.deviceName,
      customerId: data.customerId,
    };
  } catch (err) {
    throw toApiError(err);
  }
}

/**
 * PUT /api/devices/:device/customer：把设备分配或转移到另一个客户，转移时同一事务内清掉该设备的所有
 * 绑定（旧客户下绑定的人不能带着座位去新客户的设备）。仅平台管理员；设备不存在 404，客户不存在 400。
 * 设备编号放在路径里，所以要转义。失败抛 ApiError，提示由调用方给出（401 在 toApiError 里转交）。
 */
export async function assignDeviceCustomer(
  deviceName: string,
  customerId: number,
): Promise<API.AssignDeviceResult> {
  try {
    return await request<API.AssignDeviceResult>(
      `/api/devices/${encodeURIComponent(deviceName)}/customer`,
      {
        method: 'PUT',
        data: { customerId },
        skipErrorHandler: true,
      },
    );
  } catch (err) {
    throw toApiError(err);
  }
}

/**
 * GET /api/devices/:device/mini-code：设备的小程序码图片（PNG/JPEG，云端按内容判定类型），仅平台管理员
 * （yeweiyi-cloud docs/tank-grants-contract.md「设备小程序码」）。需要 Authorization 头，所以走 request 取 blob，
 * 不能用 <img src>。码只是定位入口，不授予任何权限。
 * 失败时 axios 把 body 也读成 Blob，这里先还原成 JSON，toApiError 才拿得到 `code`/`error`
 * （403 not authorized 要靠 error 文案判账号失效）。
 */
export async function getDeviceMiniCode(deviceName: string): Promise<Blob> {
  try {
    return await request<Blob>(
      `/api/devices/${encodeURIComponent(deviceName)}/mini-code`,
      { method: 'GET', responseType: 'blob', skipErrorHandler: true },
    );
  } catch (err) {
    const response = (err as { response?: { data?: unknown } })?.response;
    if (response?.data instanceof Blob) {
      try {
        response.data = JSON.parse(await response.data.text());
      } catch {
        response.data = undefined;
      }
    }
    throw toApiError(err);
  }
}
