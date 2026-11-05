import { describe, expect, it } from 'vitest';
import { buildLoginParams, normalizePhone } from './loginIdentifier';

describe('normalizePhone', () => {
  it.each([
    ['13800138000', '13800138000'],
    [' 138 0013 8000 ', '13800138000'],
    ['138-0013-8000', '13800138000'],
    ['+8613800138000', '13800138000'],
    ['008613800138000', '13800138000'],
    ['8613800138000', '13800138000'],
  ])('%s -> %s', (raw, want) => {
    expect(normalizePhone(raw)).toBe(want);
  });

  it.each(['', '1380013800', '12800138000', '+11380013800', 'admin', '86'])(
    'rejects %s',
    (raw) => {
      expect(normalizePhone(raw)).toBeUndefined();
    },
  );
});

describe('buildLoginParams', () => {
  it('sends a phone for a phone-looking identifier, and only the phone', () => {
    expect(buildLoginParams(' +86 138 0013 8000 ', 'pw')).toEqual({
      phone: '13800138000',
      password: 'pw',
    });
  });

  it('sends a username for everything else, and only the username', () => {
    const body = buildLoginParams('  admin  ', 'pw');
    expect(body).toEqual({ username: 'admin', password: 'pw' });
    expect(body).not.toHaveProperty('phone');
  });
});
