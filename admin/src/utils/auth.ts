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
  clearProfile();
  memoryToken = undefined;
  try {
    window.localStorage.removeItem(TOKEN_KEY);
  } catch {
    // 无可清理
  }
}

const PROFILE_KEY = 'yeweiyi_admin_profile';

/**
 * 登录响应里的 user 才带得出可显示的标识（令牌 sub 现在只是用户 ID）。存 {userId, label}，
 * 取用时核对 userId，换账号后不会串显示。只是显示用，不是身份依据。
 */
export function setProfile(userId: number, label: string): void {
  try {
    window.localStorage.setItem(PROFILE_KEY, JSON.stringify({ userId, label }));
  } catch {
    // 无法持久化时退化为不显示标识
  }
}

function getProfileLabel(userId: number): string {
  try {
    const raw = window.localStorage.getItem(PROFILE_KEY);
    const p = raw ? JSON.parse(raw) : undefined;
    return p?.userId === userId && typeof p.label === 'string' ? p.label : '';
  } catch {
    return '';
  }
}

function clearProfile(): void {
  try {
    window.localStorage.removeItem(PROFILE_KEY);
  } catch {
    // 无可清理
  }
}

/** 登录响应的 user 对应的显示标签：平台管理员=用户名，客户侧=手机号（本人看到完整号码）。 */
export function sessionLabel(user: API.SessionUser): string {
  return user.username ?? user.phone ?? '';
}

/**
 * 解码 JWT payload；uid / role / exp 缺失、已过期或不是 JWT 时返回 undefined。role 原样保留，由
 * access.ts 判定。sub 在 yeweiyi-cloud#59 之后是用户 ID 的字符串，不当用户名用；旧令牌里是用户名，
 * 仍可作为显示标签。
 */
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
      typeof role !== 'string' ||
      !role ||
      typeof exp !== 'number'
    ) {
      return undefined;
    }
    if (exp * 1000 <= now) return undefined;
    const legacyName =
      typeof sub === 'string' && sub && !/^\d+$/.test(sub) ? sub : '';
    return { userId: uid, username: legacyName, access: role as API.Role };
  } catch {
    return undefined;
  }
}

/**
 * 登录成功的响应是不是员工（customer_user）。员工从 #24 起可以有密码（仅用于小程序／App），所以云端
 * 会给他们签发令牌，网页必须自己拒绝。响应里的 user 和令牌 claims 任一表明是员工即拒绝：旧版云端
 * 可能不带 user，令牌里的 role 兜底。只是界面边界，服务端对管理接口的角色校验才是安全边界。
 */
export function isStaffLogin(token: string, user?: API.SessionUser): boolean {
  return (
    user?.role === 'customer_user' ||
    decodeToken(token)?.access === 'customer_user'
  );
}

/** 当前登录用户；token 无效或过期时顺便清掉，避免反复带着坏 token 请求。 */
export function getCurrentUser(): API.CurrentUser | undefined {
  const token = getToken();
  if (!token) return undefined;
  const user = decodeToken(token);
  if (!user) {
    clearToken();
    return undefined;
  }
  // 员工（customer_user）没有网页后台：令牌可能来自小程序登录或旧的本地状态，都不能进入。
  // 云端对员工的密码登录本来就拒绝，这里挡的是"手里已经有一个员工令牌"的情形。
  if (user.access === 'customer_user') {
    clearToken();
    return undefined;
  }
  return { ...user, username: user.username || getProfileLabel(user.userId) };
}

export function loginRedirectUrl(): string {
  const { pathname, search, hash } = window.location;
  return `/user/login?redirect=${encodeURIComponent(pathname + search + hash)}`;
}

/**
 * 401（账号或密码错误，含账号被停用）、403 customer_not_onboarded（所在客户尚在配置中，cloud#60，只在身份
 * 验证通过后才返回）与其余失败（400 / 5xx / 网络）分开提示。notOnboarded 是业务提示，不是账号失效。
 */
export const classifyLoginError = (
  error: any,
): 'credentials' | 'notOnboarded' | 'rateLimited' | 'failure' => {
  const status = error?.response?.status;
  if (status === 401) return 'credentials';
  // 连续失败被限流（cloud §11.4）：正确密码在窗口内也不放行，提示等待或联系平台管理员重置
  if (status === 429) return 'rateLimited';
  if (status === 403 && error.response.data?.code === 'customer_not_onboarded')
    return 'notOnboarded';
  return 'failure';
};
