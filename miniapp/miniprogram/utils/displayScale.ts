import { round2 } from './format';

// 管理员本机显示倍率（Issue #44，openspec `local-display-scale`）。只改本机、本账号的展示：
// 原始接口数据、设备设置、报警、记录与导出一律不乘。纯函数，无 `wx`，`npm run check` 在 Node 里检查。

// 乘倍率的读数：同一体积口径（油容积、水体积、空容）和质量。液位、水高、温度、占比不乘。
export const SCALED_FIELDS = ['oilVolumeL', 'oilWeightKg', 'waterVolumeL', 'nullVolumeL'] as const;
export type ScaledField = (typeof SCALED_FIELDS)[number];
export type RawScaled = Partial<Record<ScaledField, number>>;

export interface ScaleConfig {
  enabled: boolean;
  factor: number;
}

export const DEFAULT_SCALE: ScaleConfig = { enabled: false, factor: 1 };

// 只有平台管理员和客户管理员能改、能应用。员工与角色未知（token 无 role）都看原始值。
export function canUseScale(role: string): boolean {
  return role === 'platform_admin' || role === 'customer_admin';
}

// 按「账号 + 完整设备编号 + 实际罐号」隔离；任何一项缺失都不读不写（返回 ''）。
export function scaleKey(account: string, device: string, tank: number): string {
  if (!account || !device || !Number.isInteger(tank) || tank < 0) return '';
  return `display-scale:${account}:${device}#${tank}`;
}

// 合法倍率：正的有限小数（1、1.5、.5、2.）。0、负数、空、非数字、指数写法都不行。
export function parseFactor(text: string): number | null {
  const s = String(text ?? '').trim();
  if (!/^(\d+\.?\d*|\.\d+)$/.test(s)) return null;
  const v = Number(s);
  return Number.isFinite(v) && v > 0 ? v : null;
}

// 存储里读出来的值不可信（被改坏、旧版本）：形状不对就当默认。
export function readConfig(stored: unknown): ScaleConfig {
  if (!stored || typeof stored !== 'object') return { ...DEFAULT_SCALE };
  const s = stored as Record<string, unknown>;
  const factor = typeof s.factor === 'number' && Number.isFinite(s.factor) && s.factor > 0 ? s.factor : null;
  if (factor === null || typeof s.enabled !== 'boolean') return { ...DEFAULT_SCALE };
  return { enabled: s.enabled, factor };
}

// 生效的倍率；未启用为 undefined（显示原始值）。
export function activeFactor(cfg: ScaleConfig): number | undefined {
  return cfg.enabled ? cfg.factor : undefined;
}

// 原始数值先乘倍率再做显示舍入；缺失值保持缺失，不补 0。
export function scaleValue(raw: unknown, factor: number | undefined): number | undefined {
  if (typeof raw !== 'number' || !Number.isFinite(raw)) return undefined;
  return round2(factor === undefined ? raw : raw * factor);
}

// 从快照里摘出未舍入的原始值。
export function rawOf(tank: Record<string, unknown>): RawScaled {
  const raw: RawScaled = {};
  for (const f of SCALED_FIELDS) {
    const v = tank[f];
    if (typeof v === 'number' && Number.isFinite(v)) raw[f] = v;
  }
  return raw;
}

export type Scaled = { raw: RawScaled; scaleTag: string };

// 每次都从 `raw` 重新计算展示字段，所以对同一行反复应用不会叠乘。`scaleTag` 为 '' 表示原始口径，
// 否则是「×1.5」这样的标识（文字「换算显示」由页面按语言加上）。
export function applyScale<T extends Scaled>(view: T, factor: number | undefined): T {
  const out = { ...view, scaleTag: factor === undefined ? '' : `×${factor}` } as T;
  for (const f of SCALED_FIELDS) {
    (out as Record<string, unknown>)[f] = scaleValue(view.raw[f], factor);
  }
  return out;
}
