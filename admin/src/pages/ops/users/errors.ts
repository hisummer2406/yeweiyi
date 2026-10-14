import type { ErrorMessage } from '../../console/assets/devices/errors';

/**
 * 账号相关请求失败时的提示归类，按 HTTP 状态码判断，不解析后端文案；
 * 后端 {error} 文本放在 detail 里供页面拼接。0 为网络不通。
 */
export function describeUserError(
  status: number,
  error?: string,
  code?: string,
): ErrorMessage {
  // 云端新接口带机器码，先按码归类；文案不泄露其他客户的信息，所以重复手机号不说明在哪个客户。
  switch (code) {
    case 'phone_exists':
      return { id: 'pages.ops.users.error.phoneExists' };
    case 'invalid_phone':
      return { id: 'pages.ops.users.error.invalidPhone' };
    case 'password_not_allowed':
      return { id: 'pages.ops.users.error.passwordNotAllowed' };
    case 'no_password_for_role':
      return { id: 'pages.ops.users.error.noPasswordForRole' };
  }
  switch (status) {
    case 409:
      return { id: 'pages.ops.users.error.exists' };
    case 400:
      return { id: 'pages.ops.users.error.invalid', detail: error ?? '' };
    case 403:
      return { id: 'pages.ops.users.error.forbidden' };
    case 404:
      return { id: 'pages.ops.users.error.notFound' };
    case 0:
      return { id: 'pages.ops.users.error.network' };
    default:
      return { id: 'pages.ops.users.error.unknown', detail: error ?? '' };
  }
}

/** 大陆手机号（与云端规范化后的 11 位一致；输入时的空格、连字符、+86 由 normalizePhone 处理）。 */
export { normalizePhone } from '@/utils/loginIdentifier';

/** 账号的显示标识：客户侧是手机号，平台管理员是用户名。 */
export function userLabel(u: Pick<API.AdminUser, 'username' | 'phone' | 'id'>) {
  return u.username ?? u.phone ?? `#${u.id}`;
}

/** 后端密码限制：8-72 字节（不是字符数，中文密码按 UTF-8 字节算）。 */
export const PASSWORD_MIN_BYTES = 8;
export const PASSWORD_MAX_BYTES = 72;

export function passwordByteLength(password: string): number {
  return new TextEncoder().encode(password).length;
}
