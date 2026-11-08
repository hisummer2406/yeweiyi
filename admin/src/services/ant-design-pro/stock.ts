import { request } from '@umijs/max';
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
 * GET /api/oil-in-out：某台设备在窗口内的出入库记录；可选 tank / direction 由云端先筛选后分页（#84）。
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
        tank: query.tank,
        direction: query.direction,
        sort: false,
        limit: query.limit,
        offset: query.offset,
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

/** POST /api/devices/:device/stock-events 的请求体（cloud docs/stock-manual-entry-contract.md） */
export type ManualStockEntry = {
  direction: 'IN' | 'OUT';
  tank: number;
  /** 带时区的完整 RFC 3339，不得晚于现在 */
  time: string;
  beforeLevelMm: number;
  /** 两个方向都填正数 */
  volumeL: number;
  reason: string;
  /** 本次确认的幂等键：同一 (设备, requestId) 永远是同一条记录，重试复用 */
  requestId: string;
};

/**
 * 云端补录一条漏报的出入库记录（cloud#69）。201 新建、200 同一 requestId 的重放；两者都返回该行。
 * 只有平台/客户管理员可用。失败抛 ApiError（code：time_in_future、time_before_visible、request_id_reused…）。
 */
export async function addManualStockEvent(
  device: string,
  body: ManualStockEntry,
): Promise<{ row: API.StockEvent; replayed: boolean }> {
  try {
    const res = (await request<API.StockEvent>(
      `/api/devices/${encodeURIComponent(device)}/stock-events`,
      { method: 'POST', data: body, getResponse: true, skipErrorHandler: true } as Record<string, unknown>,
    )) as unknown as { data: API.StockEvent; status: number };
    return { row: res.data, replayed: res.status === 200 };
  } catch (err) {
    throw toApiError(err);
  }
}

/** DELETE /api/devices/:device/stock-events/:eventId：撤销一条云端补录（软删除，重复撤销也是 204）。设备上报的记录 409。 */
export async function deleteManualStockEvent(
  device: string,
  eventId: string,
): Promise<void> {
  try {
    await request(
      `/api/devices/${encodeURIComponent(device)}/stock-events/${encodeURIComponent(eventId)}`,
      { method: 'DELETE', skipErrorHandler: true },
    );
  } catch (err) {
    throw toApiError(err);
  }
}
