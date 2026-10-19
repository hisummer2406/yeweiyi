// Pure part of the multi-language support (no `wx`, no imports, so `npm run check` can run it in Node).
// The tables live in locales/*.ts and are wired up in i18n.ts. Design: openspec/changes/donut-android-app
// design.md D3.

export type Locale = 'zh' | 'en' | 'es';
// A text a pure module wants shown: an i18n key plus its {placeholders}. Pure modules cannot call
// `t()` (it needs `wx`), so they hand back keys and the page renders them in the current language.
export interface Msg {
  key: string;
  params?: Record<string, string | number>;
}
export type Table = Record<string, string>;
export type Tables = Record<Locale, Table>;

export const LOCALES: readonly Locale[] = ['zh', 'en', 'es'];
// A key missing from the current language falls back to this one, then to the key itself.
export const FALLBACK: Locale = 'en';

export function isLocale(value: unknown): value is Locale {
  return typeof value === 'string' && (LOCALES as readonly string[]).includes(value);
}

// The language to start in: the user's own choice if they made one, otherwise the phone's language,
// otherwise English. Phone languages arrive as 'zh_CN', 'zh-Hans', 'es-MX', 'en_US', 'es' ...
export function pickLocale(systemLanguage: string | undefined, saved: unknown): Locale {
  if (isLocale(saved)) return saved;
  const prefix = (systemLanguage || '').toLowerCase().split(/[-_]/)[0];
  return isLocale(prefix) ? prefix : FALLBACK;
}

// '{name}' placeholders. An unknown placeholder is left as written so a typo shows up on screen.
export function fill(text: string, params?: Record<string, string | number>): string {
  if (!params) return text;
  return text.replace(/\{(\w+)\}/g, (whole, name: string) =>
    Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : whole,
  );
}

export function translate(
  tables: Tables,
  locale: Locale,
  key: string,
  params?: Record<string, string | number>,
): string {
  const text = tables[locale]?.[key] ?? tables[FALLBACK]?.[key] ?? key;
  return fill(text, params);
}

// Every key of one page/area: 'login.title' -> { title }. Only single-segment remainders are taken,
// so WXML can say {{text.title}}. The fallback language fills what the current one lacks.
export function scope(tables: Tables, locale: Locale, prefix: string): Record<string, string> {
  const start = `${prefix}.`;
  const out: Record<string, string> = {};
  for (const table of [tables[FALLBACK], tables[locale]]) {
    for (const key of Object.keys(table || {})) {
      if (!key.startsWith(start)) continue;
      const rest = key.slice(start.length);
      if (rest && !rest.includes('.')) out[rest] = table[key];
    }
  }
  for (const rest of Object.keys(out)) {
    out[rest] = fill(translate(tables, locale, `${prefix}.${rest}`));
  }
  return out;
}

export function placeholdersOf(text: string): string[] {
  const names = new Set<string>();
  text.replace(/\{(\w+)\}/g, (_whole, name: string) => {
    names.add(name);
    return '';
  });
  return [...names].sort();
}

// What a consistency check needs: every language must have exactly the keys of the reference
// language (English, the fallback) and use the same placeholders in each of them.
export interface TableProblem {
  locale: Locale;
  key: string;
  problem: 'missing' | 'extra' | 'placeholders' | 'empty';
}

export function compareTables(tables: Tables): TableProblem[] {
  const problems: TableProblem[] = [];
  const ref = tables[FALLBACK];
  for (const locale of LOCALES) {
    const table = tables[locale];
    for (const key of Object.keys(ref)) {
      if (!(key in table)) {
        problems.push({ locale, key, problem: 'missing' });
      } else if (table[key].trim() === '') {
        problems.push({ locale, key, problem: 'empty' });
      } else if (placeholdersOf(table[key]).join() !== placeholdersOf(ref[key]).join()) {
        problems.push({ locale, key, problem: 'placeholders' });
      }
    }
    for (const key of Object.keys(table)) {
      if (!(key in ref)) problems.push({ locale, key, problem: 'extra' });
    }
  }
  return problems;
}
