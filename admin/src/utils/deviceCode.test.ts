import { describe, expect, it } from 'vitest';
import { normalizeDeviceCode } from './deviceCode';

describe('normalizeDeviceCode', () => {
  it('uses a full code as-is, upper-casing and trimming it', () => {
    expect(normalizeDeviceCode('ZCT000001')).toEqual({
      status: 'full',
      code: 'ZCT000001',
    });
    expect(normalizeDeviceCode('  zct000001 ')).toEqual({
      status: 'full',
      code: 'ZCT000001',
    });
  });

  it('accepts a full code with another three-letter prefix', () => {
    expect(normalizeDeviceCode('abc123')).toEqual({
      status: 'full',
      code: 'ABC123',
    });
  });

  it('turns a numeric short code into a candidate that needs confirmation, keeping leading zeros', () => {
    expect(normalizeDeviceCode('000001')).toEqual({
      status: 'needsConfirm',
      code: 'ZCT000001',
    });
  });

  it('keeps different devices apart', () => {
    const a = normalizeDeviceCode('000001');
    const b = normalizeDeviceCode('000002');
    expect(a.code).toBe('ZCT000001');
    expect(b.code).toBe('ZCT000002');
    expect(a.code).not.toBe(b.code);
  });

  it('never collapses a short code to a number', () => {
    expect(normalizeDeviceCode('007').code).toBe('ZCT007');
  });

  it.each([
    ['', 'empty'],
    ['   ', 'blank'],
  ])('reports %j as empty (%s)', (input) => {
    expect(normalizeDeviceCode(input)).toMatchObject({
      status: 'invalid',
      reason: 'empty',
    });
  });

  it.each([
    ['ZCT/01', 'slash'],
    ['ZCT?01', 'question mark'],
    ['ZCT#01', 'hash'],
    ['ZCT\\01', 'backslash'],
    ['12 34', 'inner space'],
    ['ab', 'too short'],
    ['ZCT', 'prefix only'],
    ['ZC1000', 'two letters'],
    ['ZCTA01', 'letters after digits start'],
  ])('rejects %j (%s)', (input) => {
    expect(normalizeDeviceCode(input)).toMatchObject({
      status: 'invalid',
      reason: 'format',
    });
  });
});
