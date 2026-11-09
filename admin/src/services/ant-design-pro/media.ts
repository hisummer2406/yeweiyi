import { request } from '@umijs/max';
import { toApiError } from './apiError';

/** 介质库（#27，契约 cloud/docs/media-library-contract.md）。全部接口仅平台管理员。 */
export async function listMedia(): Promise<API.Medium[]> {
  const res = await request<{ rows: API.Medium[] }>('/api/media', {
    method: 'GET',
  });
  return res.rows;
}

export async function createMedia(body: API.MediumCreate): Promise<API.Medium> {
  try {
    return await request<API.Medium>('/api/media', {
      method: 'POST',
      data: body,
      skipErrorHandler: true,
    });
  } catch (err) {
    throw toApiError(err);
  }
}

export async function updateMedia(
  id: number,
  body: API.MediumUpdate,
): Promise<API.Medium> {
  try {
    return await request<API.Medium>(`/api/media/${id}`, {
      method: 'PUT',
      data: body,
      skipErrorHandler: true,
    });
  } catch (err) {
    throw toApiError(err);
  }
}

/** 某设备可选的介质：客户由云端按设备当前归属决定。 */
export async function getDeviceMediaOptions(
  device: string,
): Promise<API.MediaOptions> {
  try {
    return await request<API.MediaOptions>(
      `/api/devices/${encodeURIComponent(device)}/media-options`,
      { method: 'GET', skipErrorHandler: true },
    );
  } catch (err) {
    throw toApiError(err);
  }
}
