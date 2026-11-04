import { request } from '@umijs/max';
import { toApiError } from './apiError';

type Body<R> = Partial<API.SyncedRecordList<R>>;

/** 缺字段时的安全默认值；syncStatus 缺失保留 undefined，界面按「无法确认」处理，不能当成已同步。 */
const toList = <R>(body: Body<R>): API.SyncedRecordList<R> => ({
  rows: Array.isArray(body.rows) ? body.rows : [],
  truncated: body.truncated === true,
  partial: body.partial === true,
  unparsable:
    typeof body.unparsable === 'number' && body.unparsable > 0
      ? body.unparsable
      : 0,
  ...(typeof body.syncStatus === 'string'
    ? { syncStatus: body.syncStatus }
    : {}),
});

/**
 * 交班/库存记录（cloud#85，docs/shift-stock-records-contract.md §4）：参数与 /api/oil-in-out 相同，
 * device 必填，tank 由云端先筛选后分页（#84）；云端默认升序，这里显式 sort=false 取最新在前，
 * 按 limit/offset 在云端分页，truncated 表示后面还有。失败抛 ApiError，提示由调用方给出（401 等鉴权失败在 toApiError 里转交；
 * 403 tank_not_authorized 不是会话失效）。
 */
const listSynced =
  <R>(path: string) =>
  async (query: API.RecordsQuery): Promise<API.SyncedRecordList<R>> => {
    try {
      const { data } = await request<Body<R>>(path, {
        method: 'GET',
        params: {
          device: query.device,
          beginTime: query.beginTime,
          endTime: query.endTime,
          tank: query.tank,
          sort: false,
          limit: query.limit,
          offset: query.offset,
        },
        getResponse: true,
        skipErrorHandler: true,
      });
      return toList(data ?? {});
    } catch (err) {
      throw toApiError(err);
    }
  };

/** GET /api/shift-records：每个 (班, 罐) 一行，时间窗按接班时间匹配，进行中的班也返回。 */
export const listShiftRecords = listSynced<API.ShiftRecord>(
  '/api/shift-records',
);

/** GET /api/stock-records：每个 (库存记录, 罐) 一行，时间窗按记录时间匹配。 */
export const listInventoryRecords = listSynced<API.InventoryRecord>(
  '/api/stock-records',
);
