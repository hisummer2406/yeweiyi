import { handleUnauthorized } from '@/requestErrorConfig';

/**
 * 自己处理错误提示的请求（skipErrorHandler）失败时抛出的错误。
 * status 为 HTTP 状态码，网络不通等没有响应的情况为 0；error 是后端 {error} 里的文本。
 */
export class ApiError extends Error {
  status: number;
  error?: string;

  constructor(status: number, error?: string) {
    super(error || `Response status:${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.error = error;
  }
}

/**
 * 把请求库抛出的错误转成 ApiError。401 仍要走全局的清 token / 回登录页，
 * 因为 skipErrorHandler 会跳过全局处理。
 */
export function toApiError(err: unknown): ApiError {
  const response = (err as { response?: { status?: number; data?: any } })
    ?.response;
  const status = response?.status ?? 0;
  if (status === 401) handleUnauthorized();
  return new ApiError(status, response?.data?.error);
}
