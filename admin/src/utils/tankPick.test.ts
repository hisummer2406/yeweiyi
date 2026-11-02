import { describe, expect, it } from 'vitest';
import { parseTankParam, pickTank } from './tankPick';

describe('parseTankParam', () => {
  it('accepts a non-negative integer and nothing else', () => {
    expect(parseTankParam('3')).toBe(3);
    expect(parseTankParam('0')).toBe(0);
    for (const v of [null, undefined, '', '-1', '1.5', 'a', '3x'])
      expect(parseTankParam(v)).toBeUndefined();
  });
});

describe('pickTank', () => {
  it('opens the tank that was asked for, by its number, not its position', () => {
    expect(pickTank([1, 3, 5], 3)).toEqual({ kind: 'tank', tank: 3 });
  });
  it('without a request picks the lowest tank', () => {
    expect(pickTank([4, 2, 9])).toEqual({ kind: 'tank', tank: 2 });
  });
  it('reports a requested tank that is not visible and shows the lowest instead', () => {
    expect(pickTank([2, 5], 4)).toEqual({ kind: 'tank', tank: 2, hidden: 4 });
  });
  it('has nothing to show when no tank is visible', () => {
    expect(pickTank([])).toEqual({ kind: 'none' });
    expect(pickTank([], 4)).toEqual({ kind: 'none', hidden: 4 });
  });
});
