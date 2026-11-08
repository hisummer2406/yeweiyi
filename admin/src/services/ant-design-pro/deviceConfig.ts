import { request } from '@umijs/max';
import { toApiError } from './apiError';

/**
 * 远程配置（cloud#3）：读设备上报的配置镜像、下发配置命令、跟踪命令、设备级开关。
 * 契约：cloud/docs/config-command-contract.md（提交保护）与 cloud/docs/tank-settings-contract.md（字段与校验）。
 * 全部 skipErrorHandler，失败抛 ApiError（401/账号失效在 toApiError 里转交全局处理），提示由调用方按 code 给出。
 */

const enc = encodeURIComponent;
const devicePath = (device: string) => `/api/devices/${enc(device)}`;

async function call<T>(
  url: string,
  options: Record<string, unknown>,
): Promise<T> {
  try {
    return await request<T>(url, { ...options, skipErrorHandler: true });
  } catch (err) {
    throw toApiError(err);
  }
}

/** GET /api/devices/:device/tank-settings：从未上报时 tanks 为空数组。 */
export async function getTankSettings(
  device: string,
): Promise<API.TankSettings[]> {
  const res = await call<{ tanks?: API.TankSettings[] }>(
    `${devicePath(device)}/tank-settings`,
    { method: 'GET' },
  );
  return Array.isArray(res?.tanks) ? res.tanks : [];
}

/** GET /api/devices/:device/device-settings：从未上报（404 device_settings_not_reported）返回 null。 */
export async function getDeviceSettings(
  device: string,
): Promise<API.DeviceSettings | null> {
  try {
    return await call<API.DeviceSettings>(
      `${devicePath(device)}/device-settings`,
      { method: 'GET' },
    );
  } catch (err) {
    if ((err as { code?: string }).code === 'device_settings_not_reported') {
      return null;
    }
    throw err;
  }
}

/** GET /api/devices/:device/tanks/:tank/calibration：未上报（404 calibration_not_reported）返回 null。 */
export async function getTankCalibration(
  device: string,
  tank: number,
): Promise<API.TankCalibration | null> {
  try {
    return await call<API.TankCalibration>(
      `${devicePath(device)}/tanks/${tank}/calibration`,
      { method: 'GET' },
    );
  } catch (err) {
    if ((err as { code?: string }).code === 'calibration_not_reported') {
      return null;
    }
    throw err;
  }
}

export type ConfigSubmission = {
  method: 'config.setTankSettings' | 'config.setDeviceSettings';
  args: Record<string, number>;
  reason: string;
  requestId: string;
  /** 表单打开时设备所在油站；不在任何油站为 null（必须显式发送） */
  expectedStationId: number | null;
};

/**
 * POST /api/devices/:device/commands：202 新受理，200 同一 requestId 的重放（返回原命令，云端不再派发）。
 * 两者都只表示受理阶段，结果看命令的 status / terminal。
 */
export async function issueConfigCommand(
  device: string,
  body: ConfigSubmission,
): Promise<{ command: API.ControlCommand; replayed: boolean }> {
  try {
    const res = await request<API.ControlCommand>(
      `${devicePath(device)}/commands`,
      {
        method: 'POST',
        data: body,
        getResponse: true,
        skipErrorHandler: true,
      } as Record<string, unknown>,
    );
    const { data, status } = res as unknown as {
      data: API.ControlCommand;
      status: number;
    };
    return { command: data, replayed: status === 200 };
  } catch (err) {
    throw toApiError(err);
  }
}

/** GET /api/devices/:device/commands/:commandId；不存在/不可见 404 command_not_found。 */
export function getCommand(
  device: string,
  commandId: string,
): Promise<API.ControlCommand> {
  return call<API.ControlCommand>(
    `${devicePath(device)}/commands/${enc(commandId)}`,
    { method: 'GET' },
  );
}

/** GET /api/devices/:device/commands?requestId=：本人这次提交，找不到返回 undefined。 */
export async function findCommandByRequestId(
  device: string,
  requestId: string,
): Promise<API.ControlCommand | undefined> {
  const res = await call<{ commands?: API.ControlCommand[] }>(
    `${devicePath(device)}/commands`,
    { method: 'GET', params: { requestId } },
  );
  return res?.commands?.[0];
}

/** GET /api/devices/:device/commands：最新在前，limit/offset 分页（云端默认 50，不给总数）。 */
export async function listCommands(
  device: string,
  limit: number,
  offset: number,
): Promise<API.ControlCommand[]> {
  const res = await call<{ commands?: API.ControlCommand[] }>(
    `${devicePath(device)}/commands`,
    { method: 'GET', params: { limit, offset } },
  );
  return Array.isArray(res?.commands) ? res.commands : [];
}
