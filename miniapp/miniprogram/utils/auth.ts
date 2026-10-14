import { post } from './request';
import { setAutoLogin, setProfile, setToken } from './storage';
import type { LoginResponse } from '../types/api';

// One WeChat code, as `wx.login` hands it out. Codes are single-use and short-lived; the cloud
// refuses a reused one, so every call below asks for a fresh code instead of caching.
function wxLoginCode(): Promise<string> {
  return new Promise((resolve, reject) => {
    wx.login({
      success: (res) => (res.code ? resolve(res.code) : reject(new Error('wx.login returned no code'))),
      fail: (err) => reject(new Error(err.errMsg || 'wx.login failed')),
    });
  });
}

function adopt(res: LoginResponse): LoginResponse {
  setToken(res.token);
  setAutoLogin(true);
  setProfile({ userId: res.user.id, phone: res.user.phone ?? '', role: res.user.role });
  return res;
}

// Restore a session for a WeChat that is already bound. 401 `wechat_not_bound` means the user must
// go through the phone authorization once. Not authorized calls: no token exists yet, so request.ts
// never treats a failure here as a session ending.
export async function wechatRestore(): Promise<LoginResponse> {
  const loginCode = await wxLoginCode();
  return adopt(await post<LoginResponse>('/api/auth/wechat/login', { loginCode }, false));
}

// First login: the phone number comes from WeChat (phoneCode), never from this app. A *new*
// loginCode is fetched here on purpose — the one used by a failed restore attempt is spent.
export async function wechatPhoneLogin(phoneCode: string): Promise<LoginResponse> {
  const loginCode = await wxLoginCode();
  return adopt(await post<LoginResponse>('/api/auth/wechat/phone-login', { loginCode, phoneCode }, false));
}

// Password login (POST /api/login): `phone` for a customer administrator, `username` for a platform
// administrator. Staff (customer_user) have no password and sign in with WeChat only. Like the
// WeChat calls it goes out without a session, so a wrong password can never end one.
export async function passwordLogin(
  body: { phone: string; password: string } | { username: string; password: string },
): Promise<LoginResponse> {
  return adopt(await post<LoginResponse>('/api/login', body, false));
}

// The JWT's "role" claim (platform_admin/customer_admin/customer_user) and "uid" claim, decoded
// client-side for display, UI gating and local storage keys only — the token is untrusted input as
// far as this app's own authorization goes, since every real check happens server-side.
//
// "sub" is NOT read: since yeweiyi-cloud#59 it is the user id as a string, and a customer-side
// account has no username at all. Who the user is comes from the login response (utils/storage
// `getProfile`), and per-user local keys use `userKeyFromToken`.
//
// One consequence worth knowing: a role change does not take effect in an already-issued token
// until the user logs in again (yeweiyi-cloud's apiClaims comment).
export function roleFromToken(token: string): string {
  const value = claimFromToken(token, 'role');
  return typeof value === 'string' ? value : '';
}

// A stable per-account key for local storage ("u12"), or '' when the token has no uid. Never a phone
// number or username: those can be reassigned, the user id cannot.
export function userKeyFromToken(token: string): string {
  const value = claimFromToken(token, 'uid');
  return typeof value === 'number' && Number.isFinite(value) ? `u${value}` : '';
}

function claimFromToken(token: string, key: 'role' | 'uid'): unknown {
  try {
    const payloadSegment = token.split('.')[1];
    const decoded = decodeBase64Url(payloadSegment);
    const claims = JSON.parse(decoded) as Record<string, unknown>;
    return claims[key];
  } catch {
    return undefined;
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
