import { beforeEach, describe, expect, it } from 'vitest';
import { clearToken, decodeToken, getToken, isTokenExpired, setToken } from './auth';

function base64url(input: string): string {
  const utf8Bytes = encodeURIComponent(input).replace(/%([0-9A-F]{2})/g, (_, hex) =>
    String.fromCharCode(Number.parseInt(hex, 16)),
  );
  return btoa(utf8Bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function makeToken(claims: Record<string, unknown>): string {
  const header = base64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = base64url(JSON.stringify(claims));
  return `${header}.${payload}.fake-signature`;
}

const validClaims = { uid: 4402, role: 'customer_user', sub: 'console-verify-empty', exp: 9999999999, iat: 1 };

describe('token storage', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('stores and retrieves a token', () => {
    setToken('abc.def.ghi');
    expect(getToken()).toBe('abc.def.ghi');
  });

  it('returns null when no token is stored', () => {
    expect(getToken()).toBeNull();
  });

  it('clears a stored token', () => {
    setToken('abc.def.ghi');
    clearToken();
    expect(getToken()).toBeNull();
  });
});

describe('decodeToken', () => {
  it('decodes a well-formed token', () => {
    const token = makeToken(validClaims);
    expect(decodeToken(token)).toEqual(validClaims);
  });

  it('decodes claims containing non-ASCII characters (e.g. Chinese username)', () => {
    const claims = { ...validClaims, sub: '张三' };
    expect(decodeToken(makeToken(claims))).toEqual(claims);
  });

  it('returns null for a token with the wrong number of segments', () => {
    expect(decodeToken('not-a-jwt')).toBeNull();
  });

  it('returns null for a payload that is not valid base64', () => {
    expect(decodeToken('header.!!!not-base64!!!.sig')).toBeNull();
  });

  it('returns null for a payload missing required claims', () => {
    expect(decodeToken(makeToken({ uid: 1 }))).toBeNull();
  });
});

describe('isTokenExpired', () => {
  it('returns false for a future exp', () => {
    expect(isTokenExpired({ exp: Math.floor(Date.now() / 1000) + 3600 })).toBe(false);
  });

  it('returns true for a past exp', () => {
    expect(isTokenExpired({ exp: Math.floor(Date.now() / 1000) - 3600 })).toBe(true);
  });
});
