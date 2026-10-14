// Pure logic of the WeChat phone login (no `wx`, so `npm run check` can run it in Node).
// The contract is yeweiyi-cloud docs/account-phone-wechat-contract.md §4.3-4.4: the client sends two
// single-use codes and never a phone number or openid; every failure comes back with a machine
// `code` that decides what the user is told and whether the phone-authorization button is offered.

export interface LoginFailure {
  message: string;
  // true only for "this WeChat is not bound yet": the next step is the phone authorization.
  offerPhone: boolean;
}

// customer-onboarding-contract §3/§4 (miniapp#5): the customer is still being configured, so no
// account under it can sign in or renew. A business notice — not a dead account, not a wrong password.
export const NOT_ONBOARDED_CODE = 'customer_not_onboarded';
export const NOT_ONBOARDED_MESSAGE = '所在客户尚未完成开通，请联系平台管理员';

const UNKNOWN: LoginFailure = { message: '登录失败，请稍后重试', offerPhone: false };

// Keyed by the cloud's `code`. None of these is the generic 403 "not authorized" that request.ts
// treats as a dead account: these calls go out without a session (needAuth=false), so a rejected
// login can never log the user out of, or bounce, a session they do not have.
const BY_CODE: Record<string, LoginFailure> = {
  wechat_not_bound: { message: '', offerPhone: true },
  phone_not_provisioned: { message: '该手机号尚未开通，请联系管理员', offerPhone: false },
  account_disabled: { message: '账号已停用，请联系管理员', offerPhone: false },
  [NOT_ONBOARDED_CODE]: { message: NOT_ONBOARDED_MESSAGE, offerPhone: false },
  wechat_identity_conflict: {
    message: '该微信或该账号已绑定其他身份，请联系管理员处理',
    offerPhone: false,
  },
  wechat_code_invalid: { message: '授权已过期，请重新点击登录', offerPhone: false },
  wechat_unavailable: { message: '微信登录暂未开启，请联系管理员', offerPhone: false },
  wechat_upstream_error: { message: '微信服务暂时不可用，请稍后重试', offerPhone: false },
  phone_unsupported: { message: '仅支持中国大陆手机号', offerPhone: false },
  invalid_request: { message: '登录请求不完整，请重试', offerPhone: false },
  // POST /api/login. One answer for wrong password, unknown account, disabled account and a role
  // that has no password (customer_user), by design: it must not reveal which of those it was.
  invalid_credentials: { message: '账号或密码错误', offerPhone: false },
};

export function describeLoginFailure(status: number, code?: string): LoginFailure {
  if (status === 0) return { message: '网络不可用，请检查网络后重试', offerPhone: false };
  if (code && BY_CODE[code]) return BY_CODE[code];
  if (status >= 500) return { message: '服务暂时不可用，请稍后重试', offerPhone: false };
  return UNKNOWN;
}

// The `code` of an error body, if there is one.
export function codeOfBody(body: unknown): string | undefined {
  const code = (body as { code?: unknown } | undefined)?.code;
  return typeof code === 'string' ? code : undefined;
}

// A request that arrived with a session and was refused because the customer is not onboarded
// (403 `customer_not_onboarded`). Distinct from the bare 403 `not authorized` (dead account).
export function isNotOnboarded(status: number, body: unknown): boolean {
  return status === 403 && codeOfBody(body) === NOT_ONBOARDED_CODE;
}

// The notice a login page is asked to show (`?notice=` on its URL), or '' for none / unknown.
export function noticeFromQuery(query: Record<string, string | undefined> | undefined): string {
  return query?.notice === NOT_ONBOARDED_CODE ? NOT_ONBOARDED_MESSAGE : '';
}

export type PhoneAuthResult =
  | { kind: 'ok'; code: string }
  | { kind: 'denied' }
  | { kind: 'failed'; message: string };

// Result of <button open-type="getPhoneNumber">. `code` is the phoneCode to send; WeChat only fills
// it on success. A refusal is a normal outcome, not an error, and must leave the user free to retry.
export function classifyPhoneAuth(detail: { code?: string; errMsg?: string } | undefined): PhoneAuthResult {
  const code = detail?.code;
  if (code) return { kind: 'ok', code };
  const errMsg = detail?.errMsg ?? '';
  if (/deny|cancel/i.test(errMsg)) return { kind: 'denied' };
  if (/no permission|privacy|not.*(supported|enabled)/i.test(errMsg)) {
    return { kind: 'failed', message: '当前小程序未开通手机号授权能力，请联系管理员' };
  }
  return { kind: 'failed', message: '获取手机号授权失败，请重试' };
}

// The password login form (POST /api/login takes exactly one of phone / username, plus password).
// Same rule as yeweiyi-admin src/utils/loginIdentifier.ts: ONE input, "手机号 / 用户名", and the
// client decides which field it is — the cloud does not guess. After stripping spaces and hyphens
// and a +86 / 0086 / 86 prefix, a mainland number (1[3-9] + 9 digits, same as the cloud's
// phone.go) goes out as `phone`; anything else is a `username`.
const PHONE = /^1[3-9]\d{9}$/;

export function normalizePhone(raw: string): string | undefined {
  const s = raw.replace(/[\s-]/g, '');
  for (const prefix of ['+86', '0086', '86']) {
    if (s.startsWith(prefix) && PHONE.test(s.slice(prefix.length))) return s.slice(prefix.length);
  }
  return PHONE.test(s) ? s : undefined;
}

export type PasswordForm =
  | { ok: true; body: { phone: string; password: string } | { username: string; password: string } }
  | { ok: false; message: string };

// Only what the cloud would reject as a 400 anyway is checked here, so an empty field is told apart
// from a wrong password. The password is never trimmed: spaces in it are the user's.
export function buildPasswordLogin(identifier: string, password: string): PasswordForm {
  const id = identifier.trim();
  if (!id) return { ok: false, message: '请输入手机号或用户名' };
  if (!password) return { ok: false, message: '请输入密码' };
  const phone = normalizePhone(id);
  return { ok: true, body: phone ? { phone, password } : { username: id, password } };
}

// 13800138000 -> 138****8000. The full number is not shown without need (issue #4).
export function maskPhone(phone: string | null | undefined): string {
  if (!phone || !/^\d{11}$/.test(phone)) return '';
  return `${phone.slice(0, 3)}****${phone.slice(7)}`;
}

// Whether a failed authorized response ends the session. Pure (no `wx`), checked in Node by
// `npm run check`.
//
// `sentToken` is the token the request went out with, `currentToken` the one stored when the answer
// arrives. They differ when the user signed in again while the request was in flight: that answer
// belongs to the old session and must not clear the new token or bounce to login. Requests that went
// out bare ('') while still signed out match a still-empty store, so they end the session as before.
export interface SessionEndInput {
  status: number;
  body: unknown;
  needAuth: boolean;
  keepSessionOn401: boolean;
  sentToken: string;
  currentToken: string;
}

export type SessionDecision = { end: false } | { end: true; notice?: string };

export function sessionDecision(i: SessionEndInput): SessionDecision {
  // Login calls (needAuth=false) have no session to end: their 401/403 are shown, not acted on.
  if (!i.needAuth) return { end: false };
  const bodyError = i.body && typeof i.body === 'object' ? (i.body as { error?: unknown }).error : undefined;
  const notOnboarded = isNotOnboarded(i.status, i.body);
  const dead =
    (i.status === 401 && !i.keepSessionOn401) || (i.status === 403 && bodyError === 'not authorized');
  if (!notOnboarded && !dead) return { end: false };
  if (i.sentToken !== i.currentToken) return { end: false };
  return notOnboarded ? { end: true, notice: NOT_ONBOARDED_CODE } : { end: true };
}
