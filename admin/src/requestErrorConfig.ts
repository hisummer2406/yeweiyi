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

export const handleRequestError = (error: any, opts: any) => {
  if (opts?.skipErrorHandler) throw error;
  if (error.response) {
    const { status, data } = error.response;
    if (status === 401) {
      // token 过期或失效：清掉并回登录页，保留当前地址以便登录后返回。
      clearToken();
      if (window.location.pathname !== '/user/login') {
        history.replace(loginRedirectUrl());
      }
      return;
    }
    // 403 等：展示后端给出的原因，不清 token。
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
