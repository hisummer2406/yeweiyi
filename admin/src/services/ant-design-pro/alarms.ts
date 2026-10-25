import { request } from '@umijs/max';
import { RECORDS_LIMIT } from '@/utils/records';
import { toApiError } from './apiError';

/**
 * GET /api/alarms：某台设备在窗口内的报警记录，响应是裸数组；首次回填未完成时带响应头
 * `X-Records-Partial: true`，所以要读响应头（getResponse）。云端默认升序，这里显式 `sort=false`
 * 取最新在前，并一次取上限 500 条，分页由前端做。可选 tank 由云端先筛选后分页（#84）；
 * 未定义的参数不会出现在 URL 上。
 * 失败抛 ApiError，提示由调用方给出，所以跳过全局错误提示（401 等鉴权失败在 toApiError 里转交）。
 */
export async function listAlarms(
  query: API.RecordsQuery,
): Promise<API.AlarmList> {
  try {
    const { data, headers } = await request<API.Alarm[]>('/api/alarms', {
      method: 'GET',
      params: {
        device: query.device,
        beginTime: query.beginTime,
        endTime: query.endTime,
        tank: query.tank,
        sort: false,
        limit: RECORDS_LIMIT,
      },
      getResponse: true,
      skipErrorHandler: true,
    });
    return {
      rows: Array.isArray(data) ? data : [],
      partial: String(headers?.['x-records-partial']).toLowerCase() === 'true',
    };
  } catch (err) {
    throw toApiError(err);
  }
}
