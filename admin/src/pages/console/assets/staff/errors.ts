import type { ErrorMessage } from '../devices/errors';

/**
 * 员工按罐授权失败的提示归类（tank-grants-contract v1.0 §4）。按 `ApiError.code` 分支，不解析 error 文案；
 * 对象级拒绝是 404/409（带 code），`403 not authorized` 仍只是账号失效（由 toApiError 转交全局，调用方拿不到）。
 * 没有 code 时按状态码退回通用提示。
 */
export function describeTankGrantError(
  status: number,
  code?: string,
  _error?: string,
): ErrorMessage {
  switch (code) {
    case 'tanks_unknown':
      return { id: 'pages.console.staff.error.tanksUnknown' };
    case 'invalid_tank':
      return { id: 'pages.console.staff.error.invalidTank' };
    case 'invalid_request':
      return { id: 'pages.console.staff.error.invalid' };
    case 'device_customer_mismatch':
      return { id: 'pages.console.staff.error.customerMismatch' };
    case 'grant_not_applicable':
      return { id: 'pages.console.staff.error.notApplicable' };
    case 'user_not_found':
      return { id: 'pages.console.staff.error.userNotFound' };
    case 'device_not_found':
      return { id: 'pages.console.staff.error.deviceNotFound' };
    default:
  }
  switch (status) {
    case 503:
      return { id: 'pages.console.staff.error.unavailable' };
    case 0:
      return { id: 'pages.console.staff.error.network' };
    default:
      return {
        id: 'pages.console.staff.error.unknown',
      };
  }
}

/** 这些结果说明页面上的员工/设备/罐已过期：需要刷新真实状态（user_not_found 还应关闭弹窗）。 */
export const isStaleGrantError = (code?: string): boolean =>
  code === 'invalid_tank' ||
  code === 'tanks_unknown' ||
  code === 'device_not_found' ||
  code === 'device_customer_mismatch' ||
  code === 'user_not_found' ||
  code === 'grant_not_applicable';
