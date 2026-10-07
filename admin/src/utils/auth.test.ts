import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  clearToken,
  decodeToken,
  getCurrentUser,
  getToken,
  setToken,
} from './auth';

const b64url = (obj: unknown) =>
  btoa(JSON.stringify(obj))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');

const makeToken = (claims: Record<string, unknown>) =>
  `${b64url({ alg: 'HS256', typ: 'JWT' })}.${b64url(claims)}.sig`;

const NOW = 1_800_000_000_000;
const future = NOW / 1000 + 3600;

describe('decodeToken', () => {
  it('decodes a valid token', () => {
    const token = makeToken({
      uid: 7,
      sub: 'alice',
      role: 'platform_admin',
      exp: future,
    });
    expect(decodeToken(token, NOW)).toEqual({
      userId: 7,
      username: 'alice',
      access: 'platform_admin',
    });
  });

  it('decodes non-ASCII usernames', () => {
    const payload = btoa(
      String.fromCharCode(
        ...new TextEncoder().encode(
          JSON.stringify({
            uid: 1,
            sub: '张三',
            role: 'customer_user',
            exp: future,
          }),
        ),
      ),
    );
    expect(decodeToken(`h.${payload}.s`, NOW)?.username).toBe('张三');
  });

  it('rejects an expired token', () => {
    const token = makeToken({
      uid: 1,
      sub: 'a',
      role: 'customer_user',
      exp: NOW / 1000 - 1,
    });
    expect(decodeToken(token, NOW)).toBeUndefined();
  });

  it('rejects non-JWT strings and empty input', () => {
    expect(decodeToken('garbage', NOW)).toBeUndefined();
    expect(decodeToken('a.!!!.c', NOW)).toBeUndefined();
    expect(decodeToken(undefined, NOW)).toBeUndefined();
    expect(decodeToken('', NOW)).toBeUndefined();
  });

  it.each(['uid', 'sub', 'role', 'exp'])('rejects a token missing %s', (k) => {
    const claims: Record<string, unknown> = {
      uid: 1,
      sub: 'a',
      role: 'customer_admin',
      exp: future,
    };
    delete claims[k];
    expect(decodeToken(makeToken(claims), NOW)).toBeUndefined();
  });

  it('keeps unknown roles as-is', () => {
    const token = makeToken({ uid: 1, sub: 'a', role: 'wizard', exp: future });
    expect(decodeToken(token, NOW)?.access).toBe('wizard');
  });
});

describe('token storage', () => {
  afterEach(() => {
    clearToken();
    vi.restoreAllMocks();
  });

  it('round-trips through localStorage', () => {
    setToken('abc');
    expect(getToken()).toBe('abc');
    expect(window.localStorage.getItem('yeweiyi_admin_token')).toBe('abc');
    clearToken();
    expect(getToken()).toBeUndefined();
  });

  it('falls back to memory when storage throws', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('denied');
    });
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('denied');
    });
    expect(() => setToken('mem')).not.toThrow();
    expect(getToken()).toBe('mem');
  });

  it('getCurrentUser clears an invalid stored token', () => {
    setToken('garbage');
    expect(getCurrentUser()).toBeUndefined();
    expect(getToken()).toBeUndefined();
  });
});
