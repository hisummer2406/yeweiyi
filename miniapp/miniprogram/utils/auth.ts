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

// The JWT's "sub" claim carries the username (see yeweiyi-cloud/internal/api/auth.go). Decoding it
// client-side is just for display — the token is opaque/untrusted input as far as this app's own
// authorization goes, since every real check happens server-side.
export function usernameFromToken(token: string): string {
  try {
    const payloadSegment = token.split('.')[1];
    const decoded = decodeBase64Url(payloadSegment);
    const claims = JSON.parse(decoded) as { sub?: string };
    return claims.sub || '';
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
