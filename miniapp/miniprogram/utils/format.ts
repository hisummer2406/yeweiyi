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
