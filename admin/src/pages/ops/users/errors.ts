import type { ErrorMessage } from '../devices/errors';

/**
 * 账号相关请求失败时的提示归类，按 HTTP 状态码判断，不解析后端文案；
 * 后端 {error} 文本放在 detail 里供页面拼接。0 为网络不通。
 */
export function describeUserError(
  status: number,
  error?: string,
): ErrorMessage {
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

/** 后端密码限制：8-72 字节（不是字符数，中文密码按 UTF-8 字节算）。 */
export const PASSWORD_MIN_BYTES = 8;
export const PASSWORD_MAX_BYTES = 72;

export function passwordByteLength(password: string): number {
  return new TextEncoder().encode(password).length;
}
