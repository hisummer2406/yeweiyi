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
export function summarizeOilInOut(
  res: OilInOutListResponse,
  direction: OilInOutDirection,
): { rows: OilInOutRow[]; note: string } {
  const rows = direction ? res.rows.filter((r) => r.direction === direction) : res.rows;

  const unparsable = res.unparsable ?? 0;
  const legacySkipped = res.legacySkipped ?? 0;
  let note = '';
  if (unparsable > 0) {
    // With a direction filter the device-wide "parsed" count would not match the list, so omit it.
    note = direction
      ? `有 ${unparsable} 条记录暂时无法解析（设备固件版本较旧），下面的列表可能不完整`
      : `有 ${unparsable} 条记录暂时无法解析（设备固件版本较旧），本页仅展示可读取的 ${res.parsed} 条`;
  } else if (legacySkipped > 0) {
    note = `另有 ${legacySkipped} 条历史记录因设备固件升级前的旧格式而无法展示`;
  }
  return { rows, note };
}
