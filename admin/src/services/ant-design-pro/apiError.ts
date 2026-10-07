import { forwardAuthFailure } from '@/requestErrorConfig';

/**
 * 自己处理错误提示的请求（skipErrorHandler）失败时抛出的错误。
 * status 为 HTTP 状态码，网络不通等没有响应的情况为 0；error 是后端 {error} 里的文本。
 * authHandled 为 true 表示这是鉴权失败（401 或账号已失效的 403），已转交全局处理回登录页，
 * 调用方不要再提示。
 */
export class ApiError extends Error {
  status: number;
  error?: string;
  authHandled: boolean;

  constructor(status: number, error?: string, authHandled = false) {
    super(error || `Response status:${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.error = error;
    this.authHandled = authHandled;
  }
}

/**
 * 把请求库抛出的错误转成 ApiError。鉴权失败（401、账号已失效的 403）仍要走全局的清 token / 回登录页，
 * 因为 skipErrorHandler 会跳过全局处理；是否属于鉴权失败由 forwardAuthFailure 判定。
 */
export function toApiError(err: unknown): ApiError {
  const response = (err as { response?: { status?: number; data?: any } })
    ?.response;
  const status = response?.status ?? 0;
  const authHandled = forwardAuthFailure(err);
  return new ApiError(status, response?.data?.error, authHandled);
}
