import { request } from '@umijs/max';
import { forwardAuthFailure } from '@/requestErrorConfig';

/**
 * 没有快照时的正常空状态（不是错误）。云端的 404 有三种，按 code 区分（yeweiyi-cloud#79）：
 * never 从未上报；expired 上报过但已超过保留期（带最后上报时间）；notProvisioned 设备接入信息未就绪。
 */
export type TelemetryEmpty = {
  empty: 'never' | 'expired' | 'notProvisioned';
  lastReportedAt?: string;
};

/** 404 体 → 空状态；不是这三种之一的 404 返回 undefined（按失败处理）。不带 code 的旧云端仍是从未上报。 */
export function telemetryEmptyFrom(data: any): TelemetryEmpty | undefined {
  switch (data?.code) {
    case undefined:
    case 'telemetry_not_reported':
      return { empty: 'never' };
    case 'telemetry_expired':
      return {
        empty: 'expired',
        lastReportedAt:
          typeof data.lastReportedAt === 'string'
            ? data.lastReportedAt
            : undefined,
      };
    case 'device_not_provisioned':
      return { empty: 'notProvisioned' };
    default:
      return undefined;
  }
}

/**
 * 最新遥测 GET /api/devices/:device/telemetry/latest（internal/api/telemetry.go）。
 * 404 是正常空状态，返回 TelemetryEmpty（见上）；其它失败原样抛出，由调用方按设备逐个展示（一台失败不影响其它）。
 * 设了 skipErrorHandler，所以 401 与账号失效在这里手动转交（清 token 回登录页）。
 */
export async function getLatestTelemetry(
  deviceName: string,
): Promise<API.LatestTelemetry | TelemetryEmpty> {
  try {
    return await request<API.LatestTelemetry>(
      `/api/devices/${encodeURIComponent(deviceName)}/telemetry/latest`,
      { method: 'GET', skipErrorHandler: true },
    );
  } catch (error: any) {
    if (error?.response?.status === 404) {
      const empty = telemetryEmptyFrom(error.response.data);
      if (empty) return empty;
    }
    forwardAuthFailure(error);
    throw error;
  }
}

/** 空状态的文案 id 与参数；time 由调用方格式化后填入 values.time。 */
export const telemetryEmptyMessageId = (e: TelemetryEmpty): string =>
  ({
    never: 'pages.console.detail.snapshot.never',
    expired: 'pages.console.detail.snapshot.expired',
    notProvisioned: 'pages.console.detail.snapshot.notProvisioned',
  })[e.empty];

export const isTelemetryEmpty = (
  r: API.LatestTelemetry | TelemetryEmpty,
): r is TelemetryEmpty => 'empty' in r;
