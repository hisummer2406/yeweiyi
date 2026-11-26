/**
 * 罐颜色色板（设备设置页的 20 色，序号 1–20）。与 cloud/fixtures/protocol/v1/oil_color_palette.json 一致（测试对照）；
 * 远程修改只能选这些颜色，设备回报的颜色可以不在色板内（旧数据）。值为 0xRRGGBB 整数。
 */
export const OIL_COLOR_PALETTE: readonly number[] = [
  0xff0000, 0xffff00, 0x0000ff, 0x008000, 0x800080, 0x254d2a, 0x6f1200,
  0x000034, 0xf4b2d6, 0x4c0b71, 0x00456c, 0x00adef, 0x342f93, 0xf4f08d,
  0xf1645d, 0xff00cd, 0x413100, 0x139300, 0xc1cd23, 0xff9900,
];

export function isPaletteColor(v: number): boolean {
  return OIL_COLOR_PALETTE.includes(v);
}

/** 0xRRGGBB → "#RRGGBB" */
export function toCss(v: number): string {
  return `#${(v & 0xffffff).toString(16).toUpperCase().padStart(6, '0')}`;
}
