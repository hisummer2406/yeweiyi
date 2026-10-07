import type { OilInOutListResponse, OilInOutRow } from '../types/api';

export type OilInOutDirection = '' | 'IN' | 'OUT';

// GET /api/oil-in-out answers the SAME envelope — {rows, parsed, unparsable, legacySkipped, ...} —
// on both its 200 and its 502 ("some records on this device could not be parsed") path; only
// GET /api/alarms answers a bare array (yeweiyi-cloud internal/api/stock.go, stockListResponse).
//
// This guard exists because `get<T>()`'s type argument is an assertion, not a check: when this page
// believed the endpoint answered a bare array, `tsc` stayed green while `.filter` ran on an object
// at runtime. A wrong shape now produces a readable message instead of a TypeError.
export function isOilInOutListResponse(v: unknown): v is OilInOutListResponse {
  return typeof v === 'object' && v !== null && Array.isArray((v as { rows?: unknown }).rows);
}

// Turns the response into what the page shows: the rows (optionally only one direction, for the
// 出库记录/入库记录 entries on 我的记录) and a note whenever the list cannot be complete, so it is
// never silently short. See yeweiyi-cloud docs/protocol-v1-stock-event.md.
//
// `paged`: the list is paged (the page asks for more when scrolled to the bottom), so "more than one
// page matched" is not a reason to doubt the list and its note is left out. The other notes describe
// the whole time window, not a page, so they stay.
export function summarizeOilInOut(
  res: OilInOutListResponse,
  direction: OilInOutDirection,
  options: { paged?: boolean } = {},
): { rows: OilInOutRow[]; note: string } {
  const rows = direction ? res.rows.filter((r) => r.direction === direction) : res.rows;

  // Each condition below means the list is not known to be whole; collect them all rather than
  // letting the first one hide the others. Fields are optional-chained because an older cloud
  // build does not send the newer ones.
  const notes: string[] = [];
  const unparsable = res.unparsable ?? 0;
  if (unparsable > 0) {
    // With a direction filter, or with paging, the "parsed" count would not match the list (it counts the
    // page just fetched, not what is shown), so omit it.
    notes.push(
      direction || options.paged
        ? `有 ${unparsable} 条记录暂时无法解析（设备固件版本较旧），下面的列表可能不完整`
        : `有 ${unparsable} 条记录暂时无法解析（设备固件版本较旧），本页仅展示可读取的 ${res.parsed} 条`,
    );
  }
  const legacySkipped = res.legacySkipped ?? 0;
  if (legacySkipped > 0) {
    notes.push(`另有 ${legacySkipped} 条历史记录因设备固件升级前的旧格式而无法展示`);
  }
  if (res.partial) {
    notes.push('设备历史记录仍在同步中，列表可能不完整');
  }
  if (res.truncated && !options.paged) {
    notes.push('符合条件的记录超过单页上限，仅显示最新的一部分');
  }
  const invalidSource = res.invalidSource ?? 0;
  if (invalidSource > 0) {
    notes.push(`有 ${invalidSource} 条记录的来源标记不合规，已略过`);
  }
  return { rows, note: notes.join('；') };
}
