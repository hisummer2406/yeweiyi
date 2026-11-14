// Run with: npm run check   (node --experimental-strip-types; pure logic only, no wx)
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { OIL_COLOR_PALETTE, isPaletteColor, toCss } from '../miniprogram/utils/oilColor.ts';

const fixture = JSON.parse(
  readFileSync(new URL('../../cloud/fixtures/protocol/v1/oil_color_palette.json', import.meta.url), 'utf8'),
);
assert.deepEqual([...OIL_COLOR_PALETTE], fixture.colors);
assert.equal(OIL_COLOR_PALETTE.length, 20);
assert.equal(toCss(16711680), '#FF0000');
assert.equal(toCss(0x0000ff), '#0000FF');
assert.equal(isPaletteColor(0xff0000), true);
assert.equal(isPaletteColor(0x123456), false);
console.log('oil colour palette checks passed');
