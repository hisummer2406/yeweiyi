import type { ErrorMessage } from '../../console/assets/devices/errors';

/**
 * 账号相关请求失败时的提示归类，按 HTTP 状态码判断，不解析后端文案；
 * 不把后端 {error} 原文给用户看（yeweiyi-cloud#79）：未归类的失败用完整的本地化兜底文案。0 为网络不通。
 */
export function describeUserError(
  status: number,
  _error?: string,
  code?: string,
): ErrorMessage {
  // 云端新接口带机器码，先按码归类；文案不泄露其他客户的信息，所以重复手机号不说明在哪个客户。
  switch (code) {
    case 'phone_exists':
      return { id: 'pages.ops.users.error.phoneExists' };
    case 'invalid_phone':
      return { id: 'pages.ops.users.error.invalidPhone' };
    case 'phone_not_applicable':
      return { id: 'pages.ops.users.error.phoneNotApplicable' };
    case 'invalid_username':
      return { id: 'pages.ops.users.error.invalidUsername' };
    case 'username_taken':
      return { id: 'pages.ops.users.error.usernameTaken' };
    case 'invalid_password':
      return { id: 'pages.ops.users.error.invalidPassword' };
  }
  switch (status) {
    case 409:
      return { id: 'pages.ops.users.error.exists' };
    case 400:
      return { id: 'pages.ops.users.error.invalid' };
    case 403:
      return { id: 'pages.ops.users.error.forbidden' };
    case 404:
      return { id: 'pages.ops.users.error.notFound' };
    case 0:
      return { id: 'pages.ops.users.error.network' };
    default:
      return { id: 'pages.ops.users.error.unknown' };
  }
}

/** 大陆手机号（与云端规范化后的 11 位一致；输入时的空格、连字符、+86 由 normalizePhone 处理）。 */
export { normalizePhone } from '@/utils/loginIdentifier';

/**
 * 客户侧用户名规则（cloud 契约 §11.2）：3–32 位 [A-Za-z0-9._-]，首字符字母或数字，不得形如大陆手机号。
 * 只是预检，云端 `invalid_username` 才是裁决（平台管理员用户名规则不同，不走这里）。
 */
export function isValidCustomerUsername(value: string): boolean {
  return (
    /^[A-Za-z0-9][A-Za-z0-9._-]{2,31}$/.test(value) &&
    !/^1[3-9]\d{9}$/.test(value)
  );
}

/** 账号的显示标识：用户名账号（平台管理员、客户侧用户名账号）是用户名，手机号账号是手机号。 */
export function userLabel(u: Pick<API.AdminUser, 'username' | 'phone' | 'id'>) {
  return u.username ?? u.phone ?? `#${u.id}`;
}

/** 后端密码限制：8-72 字节（不是字符数，中文密码按 UTF-8 字节算）。 */
export const PASSWORD_MIN_BYTES = 8;
export const PASSWORD_MAX_BYTES = 72;

export function passwordByteLength(password: string): number {
  return new TextEncoder().encode(password).length;
}
