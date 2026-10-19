import { en } from '../locales/en';
import { es } from '../locales/es';
import { zh } from '../locales/zh';
import { LOCALES, pickLocale, scope, translate } from './i18nCore';
import type { Locale, Tables } from './i18nCore';
import { getLocalePref, setLocalePref } from './storage';

export type { Locale };
export { LOCALES };

const TABLES: Tables = { zh, en, es };

// Shown in the language switcher, each in its own language so a user can find theirs.
export const LOCALE_NAMES: Record<Locale, string> = {
  zh: '中文',
  en: 'English',
  es: 'Español',
};

let current: Locale | null = null;

// The phone's language. In the WeChat mini-program this is the WeChat language; in the Donut app
// whether the field exists is verified on a device (design.md), so every read is guarded and an
// unknown language simply means English.
function systemLanguage(): string | undefined {
  try {
    const info = wx.getSystemInfoSync() as { language?: string };
    if (info && info.language) return info.language;
  } catch {
    // fall through
  }
  try {
    const base = (wx as unknown as { getAppBaseInfo?: () => { language?: string } }).getAppBaseInfo?.();
    return base?.language;
  } catch {
    return undefined;
  }
}

export function currentLocale(): Locale {
  if (!current) current = pickLocale(systemLanguage(), getLocalePref());
  return current;
}

// Remembers the choice on this phone; if storage is unavailable the language still changes for this run.
export function setLocale(locale: Locale): void {
  current = locale;
  setLocalePref(locale);
}

export function t(key: string, params?: Record<string, string | number>): string {
  return translate(TABLES, currentLocale(), key, params);
}

// All texts of one group (a page) for WXML, e.g. {{text.title}} for the key group.title.
export function texts(prefix: string): Record<string, string> {
  return scope(TABLES, currentLocale(), prefix);
}

export function tablesForCheck(): Tables {
  return TABLES;
}

// Navigation bar title of the current page. JSON files cannot be translated, so every page sets its own
// title from its onShow.
export function setNavTitle(key: string): void {
  wx.setNavigationBarTitle({ title: t(key), fail: () => {} });
}

// Labels of the three bottom tabs (app.json cannot be translated). Only valid on a tab page; elsewhere
// wx.setTabBarItem fails, which is ignored.
const TAB_KEYS = ['tab.home', 'tab.records', 'tab.settings'];

export function applyTabBar(): void {
  TAB_KEYS.forEach((key, index) => {
    wx.setTabBarItem({ index, text: t(key), fail: () => {} });
  });
}
