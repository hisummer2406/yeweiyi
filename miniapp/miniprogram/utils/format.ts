// Display rounding. The cloud passes device floats through unrounded (e.g. oilLevelMm
// 1546.4651607837066), which is noise on a phone screen. Rounds to at most 2 decimals and drops
// trailing zeros (24500 stays "24500", 12.5 stays "12.5"). Pure, no `wx`.
export function round2(n: number): number {
  return Number.isFinite(n) ? Math.round((n + Number.EPSILON) * 100) / 100 : n;
}

// Rounds only the named numeric keys of a row, so ids, indexes and timestamps are never touched.
export function roundFields<T extends object>(row: T, keys: readonly (keyof T)[]): T {
  const out = { ...row };
  for (const k of keys) {
    const v = out[k];
    if (typeof v === 'number') out[k] = round2(v) as T[keyof T];
  }
  return out;
}

// The cloud sends instants as RFC3339 UTC strings (2026-10-07T10:13:29.788Z). Showing them raw makes
// "records of October 7th" look like they run from the 6th 16:00 to the 7th 15:59 once records can be
// queried by local day, so render the phone's local wall clock instead. Anything that is not a valid
// time is returned unchanged rather than hidden or shown as "Invalid Date".
//
// The layout follows the language: year-month-day for Chinese and English (read the same in both), and
// day/month/year for Spanish. 24-hour clock everywhere. `locale` is a plain string so this stays pure.
export function formatLocalTime(iso: string, locale = 'zh'): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const pad = (n: number) => String(n).padStart(2, '0');
  const time = `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
  if (locale === 'es') {
    return `${pad(d.getDate())}/${pad(d.getMonth() + 1)}/${d.getFullYear()} ${time}`;
  }
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${time}`;
}
