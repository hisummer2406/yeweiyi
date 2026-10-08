import type { ErrorMessage } from '../devices/errors';

/**
 * 创建/编辑油站：409 同客户下重名，400 名称或地址不合法，404 油站不存在，0 网络不通。按状态码判断，不解析后端文案。
 * 对象级拒绝（别的客户的 == 不存在）云端对客户管理员答 404（yeweiyi-cloud#54、PR #55），不再与"账号已失效"共用
 * `403 not authorized`，所以和别的管理员竞争删除了油站只会得到"不存在，请刷新"，不会被踢回登录页。
 * 403：现在只剩 customer_user 调管理接口这类角色问题，其 body 同样是 `not authorized`，toApiError 会按账号失效转交全局
 * 回登录页，调用方拿不到 403。这个分支只是防御性兜底。PR #55 之前的云端构建仍答 403，见 CLAUDE.md。
 */
export function describeStationError(
  status: number,
  error?: string,
): ErrorMessage {
  switch (status) {
    case 409:
      return { id: 'pages.console.stations.error.exists' };
    case 400:
      return {
        id: 'pages.console.stations.error.invalid',
        detail: error ?? '',
      };
    case 404:
      return { id: 'pages.console.stations.error.notFound' };
    case 403:
      return { id: 'pages.console.stations.error.forbidden' };
    case 0:
      return { id: 'pages.console.stations.error.network' };
    default:
      return {
        id: 'pages.console.stations.error.unknown',
        detail: error ?? '',
      };
  }
}

/** 删除油站：409 是"油站下还有设备"，和创建时的 409（重名）含义不同，所以单独归类。 */
export function describeDeleteStationError(
  status: number,
  error?: string,
): ErrorMessage {
  if (status === 409) return { id: 'pages.console.stations.error.inUse' };
  return describeStationError(status, error);
}

/** 给设备分配油站：400 油站不属于设备所属客户，409 设备没有客户，404 设备不存在。 */
export function describeAssignStationError(
  status: number,
  error?: string,
): ErrorMessage {
  switch (status) {
    case 400:
      return { id: 'pages.console.stations.assign.error.badStation' };
    case 409:
      return { id: 'pages.console.stations.assign.error.noCustomer' };
    case 404:
      return { id: 'pages.console.stations.assign.error.notFound' };
    default:
      return describeStationError(status, error);
  }
}
