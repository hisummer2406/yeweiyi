import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { isPaletteColor, OIL_COLOR_PALETTE, toCss } from './oilColor';

describe('oil colour palette', () => {
  it('matches the shared fixture', () => {
    const fixture = JSON.parse(
      readFileSync(
        resolve(
          __dirname,
          '../../../cloud/fixtures/protocol/v1/oil_color_palette.json',
        ),
        'utf8',
      ),
    );
    expect(OIL_COLOR_PALETTE).toEqual(fixture.colors);
    expect(OIL_COLOR_PALETTE).toHaveLength(20);
  });
  it('formats and checks colours', () => {
    expect(toCss(16711680)).toBe('#FF0000');
    expect(toCss(0x0000ff)).toBe('#0000FF');
    expect(isPaletteColor(0xff0000)).toBe(true);
    expect(isPaletteColor(0x123456)).toBe(false);
  });
});
