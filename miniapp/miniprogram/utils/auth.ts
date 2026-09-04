import { post } from './request';
import { setToken } from './storage';

interface LoginResponse {
  token: string;
}

export function login(username: string, password: string): Promise<string> {
  return post<LoginResponse>('/api/login', { username, password }, false).then((res) => {
    setToken(res.token);
    return res.token;
  });
}

// The JWT's "sub" claim carries the username, and its top-level "role" claim
// (platform_admin/customer_admin/customer_user) carries the role — see yeweiyi-cloud
// internal/api/auth.go's apiClaims. Decoding either client-side is for display and UI gating only
// (e.g. hiding an admin-only control method from a customer_user before they tap it and get a 403)
// — the token is opaque/untrusted input as far as this app's own authorization goes, since every
// real check happens server-side and this app must not rely on the client-side gate as security.
//
// One consequence worth knowing: a role change does not take effect in an already-issued token
// until the user logs in again (yeweiyi-cloud's apiClaims comment) — the same caveat that repo's
// CLAUDE.md documents for itself.
export function usernameFromToken(token: string): string {
  return claimFromToken(token, 'sub');
}

export function roleFromToken(token: string): string {
  return claimFromToken(token, 'role');
}

function claimFromToken(token: string, key: 'sub' | 'role'): string {
  try {
    const payloadSegment = token.split('.')[1];
    const decoded = decodeBase64Url(payloadSegment);
    const claims = JSON.parse(decoded) as Record<string, unknown>;
    const value = claims[key];
    return typeof value === 'string' ? value : '';
  } catch {
    return '';
  }
}

function decodeBase64Url(segment: string): string {
  const normalized = segment.replace(/-/g, '+').replace(/_/g, '/');
  return wx.base64ToArrayBuffer
    ? arrayBufferToUtf8(wx.base64ToArrayBuffer(pad(normalized)))
    : '';
}

function pad(base64: string): string {
  const remainder = base64.length % 4;
  return remainder ? base64 + '='.repeat(4 - remainder) : base64;
}

function arrayBufferToUtf8(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let result = '';
  for (let i = 0; i < bytes.length; i++) {
    result += String.fromCharCode(bytes[i]);
  }
  return decodeURIComponent(escape(result));
}
