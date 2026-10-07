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
