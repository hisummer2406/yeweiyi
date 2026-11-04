import { request } from '@umijs/max';
import { toApiError } from './apiError';

/**
 * GET /api/alarms：某台设备在窗口内的报警记录，响应是裸数组；首次回填未完成时带响应头
 * `X-Records-Partial: true`，所以要读响应头（getResponse）。云端默认升序，这里显式 `sort=false`
 * 取最新在前；按 limit/offset 在云端分页。
 * 报警接口没有 truncated，所以多要一条，只显示前 limit 条。云端先按 LIMIT 取行、再跳过解析不了的行
 * （internal/api/alarms.go），要 21 条可能只回 20 条而后面其实还有，所以「回了 >= limit 条」就算还有下一页
 * （同小程序 splitAlarmPage）；代价是正好落在页边界时多一次空页请求。可选 tank 由云端先筛选后分页（#84）；
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
        limit: query.limit + 1,
        offset: query.offset,
      },
      getResponse: true,
      skipErrorHandler: true,
    });
    const rows = Array.isArray(data) ? data : [];
    return {
      rows: rows.slice(0, query.limit),
      truncated: rows.length >= query.limit,
      partial: String(headers?.['x-records-partial']).toLowerCase() === 'true',
    };
  } catch (err) {
    throw toApiError(err);
  }
}
