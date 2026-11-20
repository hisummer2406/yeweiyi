import { describe, expect, it } from 'vitest';
import { emptyTankShapes, tankShapeOf } from './tankShape';

describe('tankShapeOf oil colour', () => {
  const at = (oilColor: number | null | undefined) =>
    tankShapeOf(
      {
        rows: [{ tank: 3, tankShape: 0, oilColor, reportedAt: '' }],
        status: 'ok',
      },
      3,
    ).oilColor;

  it('follows the reported tank colour, including one outside the palette', () => {
    expect(at(0x0000ff)).toBe('#0000FF');
    expect(at(0x123456)).toBe('#123456');
  });

  it('falls back to the default oil colour when unset or invalid', () => {
    for (const v of [0, null, undefined, -1, 0x1000000, 1.5])
      expect(at(v)).toBe('');
    expect(tankShapeOf(emptyTankShapes(), 3).oilColor).toBe('');
  });
});
