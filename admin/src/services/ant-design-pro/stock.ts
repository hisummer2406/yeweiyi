import { request } from '@umijs/max';
import { RECORDS_LIMIT } from '@/utils/records';
import { toApiError } from './apiError';

/** 云端对「设备固件尚未升级」给出的稳定错误码（internal/api/stock.go）。 */
export const STOCK_EVENT_FORMAT_UNSUPPORTED = 'STOCK_EVENT_FORMAT_UNSUPPORTED';

type StockBody = Partial<
  Omit<API.StockList, 'degraded'> & {
    error: string;
    code: string;
    device: string;
    sample: string;
  }
>;

const toList = (body: StockBody, degraded?: API.StockDegraded): API.StockList => ({
  rows: Array.isArray(body.rows) ? body.rows : [],
  parsed: body.parsed ?? 0,
  unparsable: body.unparsable ?? 0,
  legacySkipped: body.legacySkipped ?? 0,
  invalidSource: body.invalidSource ?? 0,
  folded: body.folded ?? 0,
  truncated: body.truncated ?? false,
  partial: body.partial ?? false,
  ...(degraded ? { degraded } : {}),
});

/**
 * GET /api/oil-in-out：某台设备在窗口内的出入库记录（云端没有方向过滤，方向由调用方在前端过滤）。
 * 响应是对象 `{rows, parsed, unparsable, legacySkipped, ...}`。
 *
 * 云端对「不可读记录」返回 502，body 仍是同一个对象并带 `code=STOCK_EVENT_FORMAT_UNSUPPORTED`：
 * 这是降级结果而不是通用失败，所以 200 与这种 502 都返回结果对象（502 时 `degraded` 有值），
 * 页面据此显示「设备固件尚未升级」而不是一个空表。其余失败抛 ApiError（401 等鉴权失败在 toApiError 里转交）。
 */
export async function listStockEvents(
  query: API.RecordsQuery,
): Promise<API.StockList> {
  try {
    const { data } = await request<StockBody>('/api/oil-in-out', {
      method: 'GET',
      params: {
        device: query.device,
        beginTime: query.beginTime,
        endTime: query.endTime,
        sort: false,
        limit: RECORDS_LIMIT,
      },
      getResponse: true,
      skipErrorHandler: true,
    });
    return toList(data ?? {});
  } catch (err) {
    const response = (err as { response?: { status?: number; data?: StockBody } })
      ?.response;
    if (
      response?.status === 502 &&
      response.data?.code === STOCK_EVENT_FORMAT_UNSUPPORTED
    ) {
      const { code, error, device, sample } = response.data;
      return toList(response.data, { code, error, device, sample });
    }
    throw toApiError(err);
  }
}
