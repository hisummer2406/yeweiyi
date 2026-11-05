import { request } from '@umijs/max';

/**
 * 登录 POST /api/login（yeweiyi-cloud internal/api/auth.go）。
 * 成功 200 {token}；账号/密码错误或账号被停用 401 {error}；请求体不合法 400 {error}。
 * 登录请求不带 Authorization，且由登录页自己处理错误，不走全局错误提示。
 */
export async function login(
  body: API.LoginParams,
  options?: { [key: string]: any },
) {
  return request<API.LoginResult>('/api/login', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    skipAuth: true,
    skipErrorHandler: true,
    ...(options || {}),
  });
}
