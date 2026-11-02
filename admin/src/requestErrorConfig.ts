import type { RequestOptions } from '@@/plugin-request/request';
import type { RequestConfig } from '@umijs/max';
import { getIntl, history } from '@umijs/max';
import { message } from 'antd';
import { clearToken, getToken, loginRedirectUrl } from '@/utils/auth';

/**
 * yeweiyi-cloud 的响应约定：成功直接返回业务 JSON（没有 {success,data} 信封），
 * 失败为 HTTP 状态码 + {error: string}。所以成败按 HTTP 状态判定，不再用 errorThrower。
 */

/** 请求拦截：有 token 就带 Bearer；登录请求用 skipAuth 跳过。 */
export const attachAuth = (config: RequestOptions): RequestOptions => {
  if ((config as { skipAuth?: boolean }).skipAuth) return config;
  const token = getToken();
  if (token) {
    config.headers = { ...config.headers, Authorization: `Bearer ${token}` };
  }
  return config;
};

/**
 * token 过期或失效：清掉并回登录页，保留当前地址以便登录后返回。
 * 设了 skipErrorHandler 的请求不会经过 handleRequestError，自行处理错误提示时要手动调用它转交 401。
 */
export const handleUnauthorized = () => {
  clearToken();
  if (window.location.pathname !== '/user/login') {
    history.replace(loginRedirectUrl());
  }
};

/**
 * 裸 403 {error:"not authorized"}：authMiddleware 在 token 还有效但账号已被删除/停用时返回（internal/api/auth.go）。
 * 设备级拒绝的文案是 "not authorized for this device"，不会误中。
 * yeweiyi-cloud#79 起同一个 body 追加 code：account_unavailable（账号失效，回登录页）与 role_not_allowed
 * （账号正常、角色不允许该操作，不清 token，提示无权限）。不带 code 的旧云端仍按账号失效处理。
 */
export const isAccountRevoked = (error: any): boolean =>
  error?.response?.status === 403 &&
  error.response.data?.error === 'not authorized' &&
  error.response.data?.code !== 'role_not_allowed';

/** 401 或账号已失效：清 token 回登录页。返回是否已处理。 */
export const forwardAuthFailure = (error: any): boolean => {
  if (error?.response?.status === 401 || isAccountRevoked(error)) {
    handleUnauthorized();
    return true;
  }
  return false;
};

export const handleRequestError = (error: any, opts: any) => {
  if (opts?.skipErrorHandler) throw error;
  if (error.response) {
    const { status, data } = error.response;
    if (forwardAuthFailure(error)) return;
    // 客户尚在配置中（cloud#60）：业务提示，不清 token，不当作账号失效
    if (status === 403 && data?.code === 'customer_not_onboarded') {
      message.error(
        getIntl().formatMessage({
          id: 'pages.login.customerNotOnboarded',
          defaultMessage: '所在客户尚未完成开通，请联系平台管理员。',
        }),
      );
      return;
    }
    // 账号正常、角色不允许（cloud#79）：提示无权限，不清 token
    if (status === 403 && data?.code === 'role_not_allowed') {
      message.error(
        getIntl().formatMessage({
          id: 'app.request.roleNotAllowed',
          defaultMessage: '没有权限执行此操作。',
        }),
      );
      return;
    }
    // 其它 403：展示后端给出的原因，不清 token。
    message.error(data?.error || `Response status:${status}`);
  } else if (typeof navigator !== 'undefined' && !navigator.onLine) {
    message.error(
      getIntl().formatMessage({
        id: 'app.request.offline',
        defaultMessage:
          'Network unavailable. Please check your connection and try again.',
      }),
    );
  } else if (error.request) {
    message.error('None response! Please retry.');
  } else {
    message.error('Request error, please retry.');
  }
};

export const errorConfig: RequestConfig = {
  errorConfig: {
    errorHandler: handleRequestError,
  },
  requestInterceptors: [attachAuth],
  responseInterceptors: [],
};
