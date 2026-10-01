/**
 * Bearer-token session storage + JWT decode helpers.
 *
 * yeweiyi-cloud exposes no current-user endpoint — the JWT payload IS the user record
 * (`internal/api/auth.go`'s apiClaims: `{uid, role, sub, exp, iat}`). This module decodes it
 * client-side rather than verifying its signature: the app never needs to verify a token it just
 * received from the backend over HTTPS, and the backend re-verifies on every request anyway.
 */

const TOKEN_STORAGE_KEY = 'yeweiyi_token';

export type TokenClaims = {
  uid: number;
  role: string;
  sub: string;
  exp: number;
  iat: number;
};

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_STORAGE_KEY);
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_STORAGE_KEY, token);
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_STORAGE_KEY);
}

/** Decodes a JWT's payload segment. Returns null on any malformed input rather than throwing. */
export function decodeToken(token: string): TokenClaims | null {
  const segments = token.split('.');
  if (segments.length !== 3) return null;

  try {
    const base64 = segments[1].replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=');
    const json = decodeURIComponent(
      atob(padded)
        .split('')
        .map((c) => `%${c.charCodeAt(0).toString(16).padStart(2, '0')}`)
        .join(''),
    );
    const claims = JSON.parse(json);
    if (
      typeof claims?.uid !== 'number' ||
      typeof claims?.role !== 'string' ||
      typeof claims?.sub !== 'string' ||
      typeof claims?.exp !== 'number' ||
      typeof claims?.iat !== 'number'
    ) {
      return null;
    }
    return claims;
  } catch {
    return null;
  }
}

/** claims.exp is epoch seconds (JWT standard), Date.now() is epoch milliseconds. */
export function isTokenExpired(claims: Pick<TokenClaims, 'exp'>): boolean {
  return claims.exp * 1000 <= Date.now();
}
