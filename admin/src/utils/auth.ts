/**
 * 会话与当前用户。yeweiyi-cloud 只有 POST /api/login 返回 {token}，没有 currentUser / 登出接口，
 * 所以当前用户从 JWT claims（uid / sub / role / exp）解码得到。解码不验签，结果只用于界面可见性；
 * 后端每次请求都读库判定角色，不信任 token 里的 role（见 yeweiyi-cloud internal/api/auth.go）。
 */

const TOKEN_KEY = 'yeweiyi_admin_token';

// localStorage 在隐私模式等场景会抛错，降级为内存，保证页面仍可用（刷新后需重新登录）。
let memoryToken: string | undefined;

export function getToken(): string | undefined {
  try {
    return window.localStorage.getItem(TOKEN_KEY) ?? memoryToken;
  } catch {
    return memoryToken;
  }
}

export function setToken(token: string): void {
  memoryToken = token;
  try {
    window.localStorage.setItem(TOKEN_KEY, token);
  } catch {
    // 已降级到内存
  }
}

export function clearToken(): void {
  memoryToken = undefined;
  try {
    window.localStorage.removeItem(TOKEN_KEY);
  } catch {
    // 无可清理
  }
}

/** 解码 JWT payload；claim 缺失、已过期或不是 JWT 时返回 undefined。role 原样保留，由 access.ts 判定。 */
export function decodeToken(
  token: string | undefined,
  now: number = Date.now(),
): API.CurrentUser | undefined {
  if (!token) return undefined;
  const parts = token.split('.');
  if (parts.length !== 3) return undefined;
  try {
    const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const bytes = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
    const claims = JSON.parse(new TextDecoder().decode(bytes));
    const { uid, sub, role, exp } = claims ?? {};
    if (
      typeof uid !== 'number' ||
      typeof sub !== 'string' ||
      !sub ||
      typeof role !== 'string' ||
      !role ||
      typeof exp !== 'number'
    ) {
      return undefined;
    }
    if (exp * 1000 <= now) return undefined;
    return { userId: uid, username: sub, access: role as API.Role };
  } catch {
    return undefined;
  }
}

/** 当前登录用户；token 无效或过期时顺便清掉，避免反复带着坏 token 请求。 */
export function getCurrentUser(): API.CurrentUser | undefined {
  const token = getToken();
  if (!token) return undefined;
  const user = decodeToken(token);
  if (!user) clearToken();
  return user;
}

export function loginRedirectUrl(): string {
  const { pathname, search, hash } = window.location;
  return `/user/login?redirect=${encodeURIComponent(pathname + search + hash)}`;
}

/** 401（账号或密码错误，含账号被停用）与其余失败（400 / 5xx / 网络）分开提示。 */
export const classifyLoginError = (error: any): 'credentials' | 'failure' =>
  error?.response?.status === 401 ? 'credentials' : 'failure';
