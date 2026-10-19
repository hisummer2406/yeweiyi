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
// Written the way a person would say it: "today 14:05", "yesterday 09:30", "Oct 7 14:05", and the year
// only when it is not this year. No seconds, 24-hour clock. There is no "5 minutes ago": a page keeps
// its text while it stays open, so a relative age would go stale on screen. An instant ahead of `now`
// (the phone clock behind the cloud) is shown by date like any other, never as "tomorrow".
// `locale` is a plain string and `now` a parameter so this stays pure and checkable.
const WORDS: Record<string, { today: string; yesterday: string }> = {
  zh: { today: '今天', yesterday: '昨天' },
  en: { today: 'Today', yesterday: 'Yesterday' },
  es: { today: 'Hoy', yesterday: 'Ayer' },
};
const MONTHS_EN = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const MONTHS_ES = ['ene', 'feb', 'mar', 'abr', 'may', 'jun', 'jul', 'ago', 'sep', 'oct', 'nov', 'dic'];

export function formatLocalTime(iso: string, locale = 'zh', now: Date = new Date()): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const pad = (n: number) => String(n).padStart(2, '0');
  const clock = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  const words = WORDS[locale] || WORDS.en;

  const dayStart = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  const daysAgo = Math.round((dayStart(now) - dayStart(d)) / 86400000);
  if (daysAgo === 0) return `${words.today} ${clock}`;
  if (daysAgo === 1) return `${words.yesterday} ${clock}`;

  const y = d.getFullYear();
  const m = d.getMonth();
  const day = d.getDate();
  const withYear = y !== now.getFullYear();
  if (locale === 'zh') return `${withYear ? `${y}年` : ''}${m + 1}月${day}日 ${clock}`;
  if (locale === 'es') return `${day} ${MONTHS_ES[m]}${withYear ? ` ${y}` : ''} ${clock}`;
  return `${MONTHS_EN[m]} ${day}${withYear ? `, ${y}` : ''} ${clock}`;
}
