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

/**
 * 给设备分配油站（PUT /api/devices/:device/station，station-device-replacement-contract v1.0 §3.1）。
 * 按 code 分支，不解析 error 文案：409 station_occupied（detail 为 occupiedBy，占用该油站的另一台设备）、
 * 409 device_without_customer；其余 409 没有 code（旧版云端）时用覆盖多种情形的通用文案。
 * 400 油站不属于设备所属客户，404 设备不存在/不可见。
 */
export function describeAssignStationError(
  status: number,
  error?: string,
  code?: string,
  occupiedBy?: string,
): ErrorMessage {
  if (status === 409) {
    if (code === 'station_occupied')
      return {
        id: 'pages.console.stations.assign.error.occupiedBy',
        detail: occupiedBy ?? '',
      };
    if (code === 'device_without_customer')
      return { id: 'pages.console.stations.assign.error.noCustomer' };
    return { id: 'pages.console.stations.assign.error.conflict' };
  }
  switch (status) {
    case 400:
      return { id: 'pages.console.stations.assign.error.badStation' };
    case 404:
      return { id: 'pages.console.stations.assign.error.notFound' };
    default:
      return describeStationError(status, error);
  }
}

/**
 * 换机（POST /api/stations/:id/replace-device，契约 v1.1 §3.2）。409 全部按 code：station_device_changed
 * （detail 为云端当前设备 currentDeviceName，页面已过期）、device_in_other_station、device_customer_mismatch、
 * device_without_customer；没有 code 的 409 退回通用冲突文案。404 按 code 区分 station_not_found / device_not_found
 * （对客户管理员，别客户的与不存在的完全相同）；400 按 code：same_device、invalid_request。
 */
export function describeReplaceError(
  status: number,
  error?: string,
  code?: string,
  currentDeviceName?: string,
): ErrorMessage {
  if (status === 409) {
    switch (code) {
      case 'station_device_changed':
        return {
          id: 'pages.console.stations.replace.error.changed',
          detail: currentDeviceName ?? '',
        };
      case 'device_in_other_station':
        return { id: 'pages.console.stations.replace.error.otherStation' };
      case 'device_customer_mismatch':
        return { id: 'pages.console.stations.replace.error.customerMismatch' };
      case 'device_without_customer':
        return { id: 'pages.console.stations.assign.error.noCustomer' };
      default:
        return { id: 'pages.console.stations.assign.error.conflict' };
    }
  }
  if (status === 404) {
    // v1.1 起 404 带 code；没有 code（旧版云端）时退回同时说明两者的提示
    if (code === 'station_not_found')
      return { id: 'pages.console.stations.error.notFound' };
    if (code === 'device_not_found')
      return { id: 'pages.console.stations.replace.error.deviceNotFound' };
    return { id: 'pages.console.stations.replace.error.notFound' };
  }
  if (status === 400) {
    if (code === 'same_device')
      return { id: 'pages.console.stations.replace.error.sameDevice' };
    return { id: 'pages.console.stations.replace.error.invalid' };
  }
  return describeStationError(status, error);
}
