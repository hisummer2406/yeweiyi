import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  clearToken,
  decodeToken,
  getCurrentUser,
  getToken,
  setProfile,
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

  it('a token whose sub is the user id (cloud #59) carries no username', () => {
    const token = makeToken({
      uid: 12,
      sub: '12',
      role: 'customer_admin',
      exp: future,
    });
    expect(decodeToken(token, NOW)).toEqual({
      userId: 12,
      username: '',
      access: 'customer_admin',
    });
  });

  it('does not require sub at all', () => {
    const token = makeToken({ uid: 3, role: 'platform_admin', exp: future });
    expect(decodeToken(token, NOW)?.userId).toBe(3);
  });

  it.each(['uid', 'role', 'exp'])('rejects a token missing %s', (k) => {
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

describe('staff tokens and the display label', () => {
  afterEach(() => {
    clearToken();
  });

  const tokenFor = (claims: Record<string, unknown>) => {
    const exp = Math.floor(Date.now() / 1000) + 3600;
    return makeToken({ exp, ...claims });
  };

  it('a customer_user token never yields a current user and is discarded', () => {
    setToken(tokenFor({ uid: 5, sub: '5', role: 'customer_user' }));
    expect(getCurrentUser()).toBeUndefined();
    expect(getToken()).toBeUndefined();
  });

  it('shows the label stored at login for the same user id only', () => {
    setToken(tokenFor({ uid: 8, sub: '8', role: 'customer_admin' }));
    setProfile(8, '13800138000');
    expect(getCurrentUser()?.username).toBe('13800138000');

    setToken(tokenFor({ uid: 9, sub: '9', role: 'customer_admin' }));
    // 令牌换成另一个账号后，上一个账号的标签不能串过来
    expect(getCurrentUser()?.username).toBe('');
  });

  it('clearToken forgets the label', () => {
    setToken(tokenFor({ uid: 8, sub: '8', role: 'customer_admin' }));
    setProfile(8, '13800138000');
    clearToken();
    setToken(tokenFor({ uid: 8, sub: '8', role: 'customer_admin' }));
    expect(getCurrentUser()?.username).toBe('');
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
