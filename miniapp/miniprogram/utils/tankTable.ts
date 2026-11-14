// 罐表生成：按罐体形状和尺寸算出液位(mm) → 容积(L) 换算表。纯函数，无 `wx`，`npm run check` 在 Node 里检查。
//
// 照搬设备端 `android/.../factory/EllipseTable.java` 与 `fragment/CreateOilTableFragment.java#createtable_bt_build`，
// 目的是同样的输入在小程序和设备上得到同一张表（包括设备的取整与浮点细节，见各函数注释）。
// 输入框字母与设备一致（中文界面标签）：
//   卧罐  H = 罐体直径，L = 筒体长度，R = 单侧封头高度（可为 0：平封头）
//   立罐  H = 罐高，R = 设备当作直径使用（设备代码 R1 = R/2000，再 ×2 传入 diameter）
//   长方体 H = 罐高，L = 长，R = 宽
// 设备的卧罐公式只计入一侧封头（EllipseTable 注释：「若为两端封头……分别计算后求和」，设备未这么做）。

export type TankShape = 'horizontal' | 'vertical' | 'cuboid';

export const DIM_KEYS: Record<TankShape, string[]> = {
  horizontal: ['H', 'L', 'R'],
  vertical: ['H', 'R'],
  cuboid: ['H', 'L', 'R'],
};

export interface TankTable {
  heightMm: number[];
  volumeL: number[];
}

export type DimsResult =
  | { ok: true; mm: Record<string, number> }
  | { ok: false; key: string; params?: Record<string, string> };

// 设备的最小值：L/H 类尺寸输入不少于 3 位（即 ≥ 100 mm，「不能小于10厘米」）。这里按数值判断，不按字符数。
const MIN_MM = 100;
// 防止误输入算出几十万行：20 m 以内足够任何油罐（设备没有上限，但 2000 个点以上的表无法合理下发和查看）。
const MAX_MM = 20000;

function parseMm(text: string): number | null {
  const s = String(text ?? '').trim();
  if (!/^(\d+\.?\d*|\.\d+)$/.test(s)) return null;
  const v = Number(s);
  return Number.isFinite(v) ? v : null;
}

// 校验输入并转成数字（mm）。返回的错误是 i18n key（页面用 t() 渲染）。
export function parseDims(shape: TankShape, values: Record<string, string>): DimsResult {
  const mm: Record<string, number> = {};
  for (const key of DIM_KEYS[shape]) {
    const raw = values[key];
    if (raw === undefined || String(raw).trim() === '') return { ok: false, key: 'tankEdit.dimRequired', params: { dim: key } };
    const v = parseMm(raw);
    if (v === null) return { ok: false, key: 'tankEdit.dimInvalid', params: { dim: key } };
    // 卧罐的 R 是封头高度，0 = 平封头，合法；其余尺寸需 ≥ 100 mm。
    const flatHeadOk = shape === 'horizontal' && key === 'R';
    if (!flatHeadOk && v < MIN_MM) return { ok: false, key: 'tankEdit.dimTooSmall', params: { dim: key } };
    if (v > MAX_MM) return { ok: false, key: 'tankEdit.dimTooLarge', params: { dim: key } };
    mm[key] = v;
  }
  return { ok: true, mm };
}

// Java DecimalFormat 默认 HALF_EVEN：恰好在中点时取偶数。
function roundHalfEven(value: number, decimals: number): number {
  const p = 10 ** decimals;
  const s = value * p;
  const f = Math.floor(s);
  const diff = s - f;
  let r: number;
  if (diff > 0.5) r = f + 1;
  else if (diff < 0.5) r = f;
  else r = f % 2 === 0 ? f : f + 1;
  return r / p;
}

// DataUtils.doubleFoment：保留 1 位小数；格式化后超过 7 个字符（≥ 100000.0）则只保留整数。
export function doubleFoment(value: number): number {
  const one = roundHalfEven(value, 1);
  return one.toFixed(1).length > 7 ? roundHalfEven(value, 0) : one;
}

// Java 的 (int) 强转：向 0 截断。
const toInt = (x: number) => Math.trunc(x);

// 卧罐（EllipseTable 四参数版）。L、R、b 单位 m；h 用累加（h += 0.01），高度 (int)(h*1000)，与设备相同。
function horizontal(L: number, R: number, b: number): TankTable {
  const maxH = 2 * R;
  const step = 0.01;
  const size = Math.round(maxH / step);
  const heightMm: number[] = [];
  const volumeL: number[] = [];
  let h = step;
  for (let i = 0; i < size; i++) {
    const arg = Math.max(-1, Math.min(1, (R - h) / R));
    const term1 = 0.5 * Math.PI * R * R;
    const term2 = R * R * Math.asin(arg);
    const term3 = (R - h) * Math.sqrt(h * (2 * R - h));
    const vCylinder = L * (term1 - term2 - term3);
    const vHead = Math.PI * b * h * h - (Math.PI * b * h * h * h) / (3.0 * R);
    heightMm.push(toInt(h * 1000));
    volumeL.push(doubleFoment((vCylinder + vHead) * 1000));
    h += step;
  }
  return { heightMm, volumeL };
}

// 立罐（EllipseTable 两参数版）。档数 ceil(总高/0.01)，h = 0.01 + i*0.01，超过总高的档按总高算（与设备相同）。
function vertical(diameter: number, totalHeight: number): TankTable {
  const r = diameter / 2.0;
  const count = Math.max(1, Math.ceil(totalHeight / 0.01));
  const heightMm: number[] = [];
  const volumeL: number[] = [];
  for (let i = 0; i < count; i++) {
    const h = 0.01 + i * 0.01;
    const calH = Math.min(h, totalHeight);
    volumeL.push(doubleFoment(Math.PI * r * r * calH * 1000));
    heightMm.push(toInt(h * 1000));
  }
  return { heightMm, volumeL };
}

// 长方体（EllipseTable 三参数版）。容积保留 2 位小数（Math.round，与设备相同）。
function cuboid(length: number, width: number, totalHeight: number): TankTable {
  const count = Math.max(1, Math.ceil(totalHeight / 0.01));
  const heightMm: number[] = [];
  const volumeL: number[] = [];
  for (let i = 0; i < count; i++) {
    const h = 0.01 + i * 0.01;
    const calH = Math.min(h, totalHeight);
    volumeL.push(Math.round(length * width * calH * 1000 * 100) / 100.0);
    heightMm.push(toInt(h * 1000));
  }
  return { heightMm, volumeL };
}

// 输入 mm（已经过 parseDims），按设备的单位换算后生成。
export function generateTankTable(shape: TankShape, mm: Record<string, number>): TankTable {
  if (shape === 'horizontal') {
    // 设备：L = 长/1000，R1 = 直径/2000，C1 = 封头/1000，调用 (L, R1, C1)
    return horizontal(mm.L / 1000, mm.H / 2000, mm.R / 1000);
  }
  if (shape === 'vertical') {
    // 设备：L 输入框标 H（罐高），diameter 输入框标 R；调用 (R1*2, L) = (R/1000, H/1000)
    return vertical((mm.R / 2000) * 2, mm.H / 1000);
  }
  // 设备：L 输入框标 L，diameter 输入框标 R，headA 输入框标 H；调用 (L, R1*2, C1) = (L/1000, R/1000, H/1000)
  return cuboid(mm.L / 1000, (mm.R / 2000) * 2, mm.H / 1000);
}

// 预览用：点数多时按设备列表的做法抽样（≥ 200 点时每 step 个取一个），最后一行总是保留。
export function previewRows(table: TankTable, limit = 200): { heightMm: number; volumeL: number }[] {
  const n = table.heightMm.length;
  const step = n >= limit ? Math.ceil(n / limit) : 1;
  const rows: { heightMm: number; volumeL: number }[] = [];
  for (let i = 0; i < n; i++) {
    if (step === 1 || i % step === 0 || i === n - 1) rows.push({ heightMm: table.heightMm[i], volumeL: table.volumeL[i] });
  }
  return rows;
}
