import type { OilInOutListResponse, OilInOutRow } from '../types/api';
import type { Msg } from './i18nCore';

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
// stock-out / stock-in entries of the records tab) and notes whenever the list cannot be complete, so it
// is never silently short. The notes are i18n keys with their numbers (locales/*.ts, `oilNote`); the page
// renders them in the current language. See yeweiyi-cloud docs/protocol-v1-stock-event.md.
//
// `paged`: the list is paged (the page asks for more when scrolled to the bottom), so "more than one
// page matched" is not a reason to doubt the list and its note is left out. The other notes describe
// the whole time window, not a page, so they stay.
export function summarizeOilInOut(
  res: OilInOutListResponse,
  direction: OilInOutDirection,
  options: { paged?: boolean } = {},
): { rows: OilInOutRow[]; notes: Msg[] } {
  const rows = direction ? res.rows.filter((r) => r.direction === direction) : res.rows;

  // Each condition below means the list is not known to be whole; collect them all rather than
  // letting the first one hide the others. Fields are optional-chained because an older cloud
  // build does not send the newer ones.
  const notes: Msg[] = [];
  const unparsable = res.unparsable ?? 0;
  if (unparsable > 0) {
    // With a direction filter, or with paging, the "parsed" count would not match the list (it counts the
    // page just fetched, not what is shown), so omit it.
    notes.push(
      direction || options.paged
        ? { key: 'oilNote.unparsableMaybe', params: { n: unparsable } }
        : { key: 'oilNote.unparsableShown', params: { n: unparsable, parsed: res.parsed } },
    );
  }
  const legacySkipped = res.legacySkipped ?? 0;
  if (legacySkipped > 0) notes.push({ key: 'oilNote.legacySkipped', params: { n: legacySkipped } });
  if (res.partial) notes.push({ key: 'oilNote.partial' });
  if (res.truncated && !options.paged) notes.push({ key: 'oilNote.truncated' });
  const invalidSource = res.invalidSource ?? 0;
  if (invalidSource > 0) notes.push({ key: 'oilNote.invalidSource', params: { n: invalidSource } });
  return { rows, notes };
}
