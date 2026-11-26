import { round2 } from './format';
import { DIM_KEYS, generateTankTable, tableValid, type TankShape, type TankTable } from './tankTable';

// 虚拟罐表（Issue #56，openspec `virtual-tank-table`，替代 #44 显示倍率）：管理员在「罐表设置」按尺寸生成一张罐表，
// 绑定到某台设备的某个实际罐，本机首页/详情的容积改按「设备上报的液位 → 这张完整罐表」查出来显示。
// 只改本机、本账号的展示：设备罐表、原始遥测、报警、记录与导出一律不动。纯函数，无 `wx`，`npm run check` 在 Node 里检查。
//
// 设备口径（android `factory/LDCompany.java` 等探针解析、`docs/ThingsBoard两版迭代执行方案.md`）：
//   oilVolumeL  = 按油面高度（总液面，含底水）查罐表得到的总体积；
//   oilWeightKg = oilVolumeL × 密度（设备设置，无温度补偿）；
//   nullVolumeL = 容量 − 总体积；waterVolumeL 真实模式恒为 0；oilRatioPct 由液位/罐高算，与罐表无关。
// 虚拟显示沿用同一口径，只把「罐表」换成虚拟表：
//   容积 = V虚(液位)；质量 = V虚 × 设备的有效密度（原质量 ÷ 原容积）；空容 = 虚拟表末点容积 − V虚；
//   水体积 = V虚(水高)（设备没有水高时保持原值）；液位、水高、温度、占比、罐图填充保持设备原值。

export const VIRTUAL_FIELDS = ['oilVolumeL', 'oilWeightKg', 'waterVolumeL', 'nullVolumeL'] as const;
export type VirtualField = (typeof VIRTUAL_FIELDS)[number];
export type RawReadings = Partial<Record<VirtualField | 'oilLevelMm' | 'waterLevelMm', number>>;

export interface VirtualTable {
  shape: TankShape;
  // 生成时的尺寸（mm），只用于展示「这张表是怎么来的」
  dims: Record<string, number>;
  heightMm: number[];
  volumeL: number[];
  // 启用时间（ISO）
  enabledAt: string;
}

// 只有平台管理员和客户管理员能启用、能应用。员工与角色未知（token 无 role）都看设备原值。
export function canUseVirtual(role: string): boolean {
  return role === 'platform_admin' || role === 'customer_admin';
}

// 按「账号 + 完整设备编号 + 实际罐号」隔离；任何一项缺失都不读不写（返回 ''）。
export function virtualKey(account: string, device: string, tank: number): string {
  if (!account || !device || !Number.isInteger(tank) || tank < 0) return '';
  return `virtual-table:${account}:${device}#${tank}`;
}

// 旧 #44 的倍率键（`display-scale:<账号>:...`）。升级后不再参与计算，只用来提示一次并清掉。
export function legacyScaleKeys(keys: readonly string[], account: string): string[] {
  if (!account) return [];
  const prefix = `display-scale:${account}:`;
  return keys.filter((k) => k.startsWith(prefix));
}

// 可用的罐表：至少 2 点，两列等长、都是有限数，液位严格递增且为正，容积不减。
export function tableUsable(table: { heightMm?: unknown; volumeL?: unknown } | null | undefined): table is TankTable {
  if (!table || !Array.isArray(table.heightMm) || !Array.isArray(table.volumeL)) return false;
  const h = table.heightMm as unknown[];
  const v = table.volumeL as unknown[];
  if (h.length < 2 || h.length !== v.length) return false;
  for (let i = 0; i < h.length; i++) {
    const hi = h[i];
    const vi = v[i];
    if (typeof hi !== 'number' || typeof vi !== 'number' || !Number.isFinite(hi) || !Number.isFinite(vi)) return false;
    if (vi < 0) return false;
    if (i === 0 ? hi <= 0 : hi <= (h[i - 1] as number) || vi < (v[i - 1] as number)) return false;
  }
  return true;
}

// 生成一张待启用的虚拟表（尺寸已经过 parseDims）。设备公式算不出有效表时返回 null。
export function buildVirtualTable(shape: TankShape, mm: Record<string, number>, now: Date): VirtualTable | null {
  const table = generateTankTable(shape, mm);
  if (!tableValid(table) || !tableUsable(table)) return null;
  const dims: Record<string, number> = {};
  for (const k of DIM_KEYS[shape]) dims[k] = mm[k];
  return { shape, dims, heightMm: table.heightMm, volumeL: table.volumeL, enabledAt: now.toISOString() };
}

// 存储里读出来的值不可信（被改坏、旧版本）：形状不对就当没有（null）。
export function readVirtual(stored: unknown): VirtualTable | null {
  if (!stored || typeof stored !== 'object') return null;
  const s = stored as Record<string, unknown>;
  if (typeof s.shape !== 'string' || !(s.shape in DIM_KEYS)) return null;
  if (!tableUsable(s as { heightMm?: unknown; volumeL?: unknown })) return null;
  if (typeof s.enabledAt !== 'string' || !s.dims || typeof s.dims !== 'object') return null;
  const dims: Record<string, number> = {};
  for (const k of DIM_KEYS[s.shape as TankShape]) {
    const v = (s.dims as Record<string, unknown>)[k];
    if (typeof v !== 'number' || !Number.isFinite(v)) return null;
    dims[k] = v;
  }
  return {
    shape: s.shape as TankShape,
    dims,
    heightMm: (s.heightMm as number[]).slice(),
    volumeL: (s.volumeL as number[]).slice(),
    enabledAt: s.enabledAt,
  };
}

export type Lookup = { ok: true; volumeL: number } | { ok: false; reason: 'missing' | 'range' };

// 液位 → 容积，用完整表（不是预览抽样）。规则（openspec 固定）：
// - 缺失/非数字 → missing（不补 0）；负数或高于末点 → range（不外推、不截到满罐）。
// - 0 → 0（空罐）；0 与首点之间按 (0, 0) 到首点线性插值（几何表在 0 液位体积为 0）。
// - 命中表点取该点；两点之间分段线性插值（与设备 DataUtils.getVoulme 相同）。
export function lookupVolume(table: TankTable, levelMm: unknown): Lookup {
  if (typeof levelMm !== 'number' || !Number.isFinite(levelMm)) return { ok: false, reason: 'missing' };
  const h = table.heightMm;
  const v = table.volumeL;
  const n = h.length;
  if (levelMm < 0 || levelMm > h[n - 1]) return { ok: false, reason: 'range' };
  if (levelMm === 0) return { ok: true, volumeL: 0 };
  if (levelMm <= h[0]) return { ok: true, volumeL: (v[0] * levelMm) / h[0] };
  // 二分找 h[i] ≤ level ≤ h[i+1]
  let lo = 0;
  let hi = n - 1;
  while (hi - lo > 1) {
    const mid = (lo + hi) >> 1;
    if (h[mid] <= levelMm) lo = mid;
    else hi = mid;
  }
  if (levelMm === h[lo]) return { ok: true, volumeL: v[lo] };
  if (levelMm === h[hi]) return { ok: true, volumeL: v[hi] };
  return { ok: true, volumeL: v[lo] + ((v[hi] - v[lo]) * (levelMm - h[lo])) / (h[hi] - h[lo]) };
}

// 虚拟表的满罐容积（末点）。
export function tableCapacity(table: TankTable): number {
  return table.volumeL[table.volumeL.length - 1];
}

// '' = 设备原值（未启用）；'virtual' = 按虚拟表显示；'fallback' = 已启用但这次算不了，显示的是设备原值。
export type VirtualState = '' | 'virtual' | 'fallback';

export type VirtualResult = {
  state: VirtualState;
  // fallback 的原因（i18n key 后缀：missing / range）
  reason: '' | 'missing' | 'range';
  values: Partial<Record<VirtualField, number>>;
};

const isNum = (x: unknown): x is number => typeof x === 'number' && Number.isFinite(x);

// 按虚拟表重算四项读数（未舍入）。table 为 null：设备原值。液位缺失或越界：fallback，四项都用设备原值，
// 不混用两张表。质量需要设备的有效密度（原质量 ÷ 原容积）：原容积不是正数时质量缺失（显示「—」），除非虚拟容积为 0。
export function virtualReadings(raw: RawReadings, table: TankTable | null): VirtualResult {
  const original: Partial<Record<VirtualField, number>> = {};
  for (const f of VIRTUAL_FIELDS) if (isNum(raw[f])) original[f] = raw[f];
  if (!table) return { state: '', reason: '', values: original };
  const oil = lookupVolume(table, raw.oilLevelMm);
  if (!oil.ok) return { state: 'fallback', reason: oil.reason, values: original };
  const values: Partial<Record<VirtualField, number>> = { oilVolumeL: oil.volumeL };
  values.nullVolumeL = tableCapacity(table) - oil.volumeL;
  if (oil.volumeL === 0) values.oilWeightKg = 0;
  else if (isNum(raw.oilWeightKg) && isNum(raw.oilVolumeL) && raw.oilVolumeL > 0) {
    values.oilWeightKg = (oil.volumeL * raw.oilWeightKg) / raw.oilVolumeL;
  }
  const water = lookupVolume(table, raw.waterLevelMm);
  if (water.ok) values.waterVolumeL = water.volumeL;
  else if (isNum(raw.waterVolumeL)) values.waterVolumeL = raw.waterVolumeL;
  return { state: 'virtual', reason: '', values };
}

// 从快照里摘出未舍入的原始值。
export function rawOf(tank: Record<string, unknown>): RawReadings {
  const raw: RawReadings = {};
  for (const f of [...VIRTUAL_FIELDS, 'oilLevelMm', 'waterLevelMm'] as const) {
    const v = tank[f];
    if (isNum(v)) raw[f] = v;
  }
  return raw;
}

export type Virtualized = { raw: RawReadings; virtualState: VirtualState; virtualReason: '' | 'missing' | 'range' };

// 每次都从 `raw` 重新计算展示字段，所以对同一行反复应用不会叠加。
export function applyVirtual<T extends Virtualized>(view: T, table: TankTable | null): T {
  const r = virtualReadings(view.raw, table);
  const out = { ...view, virtualState: r.state, virtualReason: r.reason } as T;
  for (const f of VIRTUAL_FIELDS) {
    const v = r.values[f];
    (out as Record<string, unknown>)[f] = isNum(v) ? round2(v) : undefined;
  }
  return out;
}
