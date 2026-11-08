/**
 * 记录里的「介质」（cloud#85 §5）：云端同时给 oilType（设备当时的显示名）与 oilTypeIndex（设备油品表下标，与语言无关）。
 * 下标 1–31 是内置油品，按界面语言显示本地名称（对照表 cloud/fixtures/protocol/v1/oil_type_index.json，
 * 以中文名为准：设备端英文表有错位，zhongchengyeweiyi#52；设备英文名是缩写，这里用全称）；0 是未设置，32–39 是
 * 站点在设备上自定义的名称，只能显示 oilType 原文；原文也没有显示「—」。
 * 18–25 在旧固件（测试包 D 及以前）上是自定义油品（#11），协议里没有固件版本可区分：只有 oilType 与新固件该下标的
 * 设备原名（中文或设备英文缩写）一致时才按内置介质本地化，否则按自定义处理（显示原文，原文为空显示「—」）。
 * 与小程序 utils/recordColumns.ts 的 mediumText 一致。
 */
import { DASH } from './records';

export const BUILTIN_OIL_MAX = 31;

/**
 * 旧固件把 18–25 当自定义油品（#11）。这一段只有设备原文等于新固件该下标的设备原名（中文或设备英文）时，才断定是新的
 * 内置介质；名单取自 cloud/fixtures/protocol/v1/oil_type_index.json，改 fixture 要同步。
 */
const LEGACY_CUSTOM_DEVICE_NAMES: Record<number, readonly string[]> = {
  18: ['丙二醇', 'PG'],
  19: ['乙醇', 'Ethanol'],
  20: ['大豆油', 'Soy Oil'],
  21: ['棕榈油', 'Palm Oil'],
  22: ['轮胎油', 'Tire Oil'],
  23: ['石蜡', 'Paraffin'],
  24: ['二氯甲烷', 'DCM'],
  25: ['二厂盐酸', 'P2 HCl'],
};

function hasText(raw: unknown): raw is string {
  return typeof raw === 'string' && raw.trim() !== '';
}

/** 内置油品的文案 id（pages.console.oil.N）；不是 1–31 的整数返回 null。 */
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
  const deviceNames =
    typeof index === 'number' ? LEGACY_CUSTOM_DEVICE_NAMES[index] : undefined;
  const legacyCustom =
    deviceNames !== undefined &&
    !(hasText(raw) && deviceNames.includes(raw.trim()));
  if (key && !legacyCustom) return tr(key);
  return hasText(raw) ? raw : DASH;
}
