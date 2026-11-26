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
    // axios 0.28+ 的 headers 类型是 AxiosHeaders；展开成普通对象再交回，运行时 axios 会重新规整。
    const headers = (config.headers ?? {}) as Record<string, unknown>;
    config.headers = {
      ...headers,
      Authorization: `Bearer ${token}`,
    } as RequestOptions['headers'];
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

/**
 * 未被页面单独处理的失败的兜底文案：按云端 code，再按 HTTP 状态（cloud docs/error-codes-contract.md）。
 * 不承诺原因：只有确实是平台/服务不可用的码才说"暂时不可用"。
 */
export const fallbackMessage = (
  status: number,
  code?: string,
): { id: string; defaultMessage: string } => {
  if (code === 'device_platform_unavailable')
    return {
      id: 'app.request.platformUnavailable',
      defaultMessage: '设备数据服务暂时不可用，请稍后重试。',
    };
  if (code === 'command_delivery_unconfirmed')
    return {
      id: 'app.request.commandUnconfirmed',
      defaultMessage: '执行结果尚未确认，请先查看操作记录，不要直接重复提交。',
    };
  if (status === 400)
    return {
      id: 'app.request.invalid',
      defaultMessage: '提交内容无效，请检查后重试。',
    };
  if (status === 403)
    return {
      id: 'app.request.roleNotAllowed',
      defaultMessage: '没有权限执行此操作。',
    };
  if (status === 404)
    return {
      id: 'app.request.notFound',
      defaultMessage: '要操作的对象不存在，请刷新后重试。',
    };
  if (status === 409)
    return {
      id: 'app.request.conflict',
      defaultMessage: '数据已变化，请刷新后重试。',
    };
  if (status === 429)
    return {
      id: 'app.request.tooMany',
      defaultMessage: '操作太频繁，请稍后再试。',
    };
  return {
    id: 'app.request.unknown',
    defaultMessage: '操作失败，请稍后重试；持续失败请联系平台管理员。',
  };
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
    // 其余失败：按状态给本地化兜底，不展示后端原文（yeweiyi-cloud#79）
    message.error(getIntl().formatMessage(fallbackMessage(status, data?.code)));
  } else if (typeof navigator !== 'undefined' && !navigator.onLine) {
    message.error(
      getIntl().formatMessage({
        id: 'app.request.offline',
        defaultMessage:
          'Network unavailable. Please check your connection and try again.',
      }),
    );
  } else if (error.request) {
    // 发出去了没有响应：超时或连接被断
    message.error(
      getIntl().formatMessage({
        id: 'app.request.noResponse',
        defaultMessage: '网络不可用或请求超时，请检查网络后重试。',
      }),
    );
  } else {
    // 请求没能发出（原因未知）：不说成网络问题
    message.error(getIntl().formatMessage(fallbackMessage(-1)));
  }
};

export const errorConfig: RequestConfig = {
  errorConfig: {
    errorHandler: handleRequestError,
  },
  requestInterceptors: [attachAuth],
  responseInterceptors: [],
};
