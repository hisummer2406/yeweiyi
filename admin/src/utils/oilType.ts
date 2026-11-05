/**
 * 记录里的「介质」（cloud#85 §5）：云端同时给 oilType（设备当时的显示名）与 oilTypeIndex（设备油品表下标，与语言无关）。
 * 下标 1–17 是内置油品，按界面语言显示本地名称（对照表 yeweiyi-cloud fixtures/protocol/v1/oil_type_index.json，
 * 以中文名为准：设备端英文表有错位，zhongchengyeweiyi#52）；0 是未设置，18–25 是站点在设备上自定义的名称，
 * 只能显示 oilType 原文；原文也没有显示「—」。与小程序 utils/recordColumns.ts 的 mediumText 一致。
 */
import { DASH } from './records';

export const BUILTIN_OIL_MAX = 17;

/** 内置油品的文案 id（pages.console.oil.N）；不是 1–17 的整数返回 null。 */
export function oilTypeKey(index: unknown): string | null {
  return typeof index === 'number' &&
    Number.isInteger(index) &&
    index >= 1 &&
    index <= BUILTIN_OIL_MAX
    ? `pages.console.oil.${index}`
    : null;
}

/** 介质单元格：先按下标本地化，否则设备原文，否则「—」。`tr` 把文案 id 渲染成当前语言。 */
export function mediumText(
  index: unknown,
  raw: unknown,
  tr: (id: string) => string,
): string {
  const key = oilTypeKey(index);
  if (key) return tr(key);
  return typeof raw === 'string' && raw.trim() ? raw : DASH;
}
