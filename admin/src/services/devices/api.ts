// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/** 获取调用者名下绑定的设备 GET /api/devices */
export async function listDevices(options?: { [key: string]: any }) {
  return request<DeviceAPI.ListDevicesResponse>('/api/devices', {
    method: 'GET',
    ...(options || {}),
  });
}

/**
 * 获取设备最新遥测快照 GET /api/devices/:device/telemetry/latest
 *
 * 404 是"已授权、已配网但从未上报过数据"的正常状态，不是错误——调用方应捕获后按状态码区分处理，
 * 而不是让全局错误提示弹出来。因此这里透传 skipErrorHandler，交由调用方决定如何呈现。
 */
export async function getLatestTelemetry(deviceName: string, options?: { [key: string]: any }) {
  return request<DeviceAPI.LatestTelemetryResponse>(
    `/api/devices/${encodeURIComponent(deviceName)}/telemetry/latest`,
    {
      method: 'GET',
      skipErrorHandler: true,
      ...(options || {}),
    },
  );
}
