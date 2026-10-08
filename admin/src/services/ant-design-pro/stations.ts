import { request } from '@umijs/max';
import { toApiError } from './apiError';

/**
 * GET /api/stations：调用者能看到的油站，响应 {rows}（internal/api/stations.go）。平台管理员看全部客户的，
 * 客户管理员看本客户全部（含还没有设备的空油站）。
 */
export async function listStations(options?: {
  silent?: boolean;
}): Promise<API.Station[]> {
  if (!options?.silent) {
    const res = await request<{ rows: API.Station[] }>('/api/stations', {
      method: 'GET',
    });
    return res.rows;
  }
  // silent：只用于仅作显示、失败即退化的调用方（旧版云端没有该接口，会答 404），不弹全局错误提示；
  // 401 / 账号失效的 403 仍经 toApiError 转交全局回登录页。
  try {
    const res = await request<{ rows: API.Station[] }>('/api/stations', {
      method: 'GET',
      skipErrorHandler: true,
    });
    return res.rows;
  } catch (err) {
    throw toApiError(err);
  }
}

/**
 * POST /api/stations：创建油站，201 返回油站。平台管理员必须带 customerId（缺 → 400）；客户管理员省略即本客户，
 * 带别的客户的 → 400 unknown customerId（云端 PR #55 起；此前是 403）。同客户重名 409，名称/地址不合法 400。失败抛 ApiError，提示由调用方给出。
 */
export async function createStation(body: {
  customerId?: number;
  name: string;
  address?: string;
}): Promise<API.Station> {
  try {
    return await request<API.Station>('/api/stations', {
      method: 'POST',
      data: body,
      skipErrorHandler: true,
    });
  } catch (err) {
    throw toApiError(err);
  }
}

/** PUT /api/stations/:id：改名/改地址（省略 address 即清空）。重名 409，不合法 400，不存在/不可见 404（云端 PR #55 起；此前客户管理员得到 403）。 */
export async function updateStation(
  id: number,
  body: { name: string; address?: string },
): Promise<API.Station> {
  try {
    return await request<API.Station>(`/api/stations/${id}`, {
      method: 'PUT',
      data: body,
      skipErrorHandler: true,
    });
  } catch (err) {
    throw toApiError(err);
  }
}

/** DELETE /api/stations/:id：204；油站下仍有设备时 409。 */
export async function deleteStation(id: number): Promise<void> {
  try {
    await request(`/api/stations/${id}`, {
      method: 'DELETE',
      skipErrorHandler: true,
    });
  } catch (err) {
    throw toApiError(err);
  }
}

/**
 * PUT /api/devices/:device/station：把设备分配到油站，stationId 为 null 表示移出油站（未分组）。
 * 这个键必须存在：省略 stationId 云端返回 400，所以清空时发送显式的 null。油站不属于设备所属客户 400，
 * 设备没有客户 409（仅平台管理员），设备不存在/不可见 404（云端 PR #55 起对所有角色一致；此前客户管理员为 403）。设备编号在路径里，要转义。
 */
export async function assignDeviceStation(
  deviceName: string,
  stationId: number | null,
): Promise<API.AssignStationResult> {
  try {
    return await request<API.AssignStationResult>(
      `/api/devices/${encodeURIComponent(deviceName)}/station`,
      {
        method: 'PUT',
        data: { stationId },
        skipErrorHandler: true,
      },
    );
  } catch (err) {
    throw toApiError(err);
  }
}
