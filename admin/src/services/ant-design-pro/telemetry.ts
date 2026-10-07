import { request } from '@umijs/max';
import { forwardAuthFailure } from '@/requestErrorConfig';

/**
 * 最新遥测 GET /api/devices/:device/telemetry/latest（internal/api/telemetry.go）。
 * 404 表示"已授权、已开通，但从未上报"，是正常状态而非错误，返回 null；
 * 其它失败原样抛出，由调用方按设备逐个展示（一台失败不影响其它）。
 * 设了 skipErrorHandler，所以 401 与账号失效在这里手动转交（清 token 回登录页）。
 */
export async function getLatestTelemetry(
  deviceName: string,
): Promise<API.LatestTelemetry | null> {
  try {
    return await request<API.LatestTelemetry>(
      `/api/devices/${encodeURIComponent(deviceName)}/telemetry/latest`,
      { method: 'GET', skipErrorHandler: true },
    );
  } catch (error: any) {
    const status = error?.response?.status;
    if (status === 404) return null;
    forwardAuthFailure(error);
    throw error;
  }
}
