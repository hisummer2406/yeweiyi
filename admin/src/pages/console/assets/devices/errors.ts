/**
 * 登记设备 / 创建客户失败时的提示归类。按 HTTP 状态码判断，不解析后端文案；
 * 返回 i18n 文案 id，后端 {error} 文本放在 detail 里供页面拼接。
 */
export type ErrorMessage = {
  id: string;
  detail?: string;
};

/** 登记设备（POST /api/devices）：409 其他客户已拥有，400 参数/客户无效，403 无权限，0 为网络不通。 */
export function describeRegisterError(
  status: number,
  error?: string,
): ErrorMessage {
  switch (status) {
    case 409:
      return { id: 'pages.ops.devices.error.conflict' };
    case 400:
      return { id: 'pages.ops.devices.error.invalid', detail: error ?? '' };
    case 403:
      return { id: 'pages.ops.devices.error.forbidden' };
    case 0:
      return { id: 'pages.ops.devices.error.network' };
    default:
      return { id: 'pages.ops.devices.error.unknown', detail: error ?? '' };
  }
}

/** 创建客户（POST /api/customers）：409 名称已存在，400 名称不合法，其余同登记。 */
export function describeCustomerError(
  status: number,
  error?: string,
): ErrorMessage {
  switch (status) {
    case 409:
      return { id: 'pages.ops.devices.customer.error.exists' };
    case 400:
      return {
        id: 'pages.ops.devices.customer.error.invalid',
        detail: error ?? '',
      };
    default:
      return describeRegisterError(status, error);
  }
}

/** 分配/转移设备（PUT /api/devices/:device/customer）：404 设备不存在，其余同登记。 */
export function describeAssignError(
  status: number,
  error?: string,
): ErrorMessage {
  if (status === 404) return { id: 'pages.ops.devices.error.notFound' };
  return describeRegisterError(status, error);
}

/**
 * 下载小程序码（GET /api/devices/:device/mini-code）：按云端机器码分支，不解析文案。
 * 404 device_not_found 设备不存在；503 wechat_unavailable 云端未启用微信；502 wechat_upstream_error 微信侧失败。
 * 恰好 `403 not authorized` 是账号失效，已由 toApiError 转交全局，到不了这里。
 */
export function describeMiniCodeError(
  status: number,
  code?: string,
  error?: string,
): ErrorMessage {
  switch (code) {
    case 'device_not_found':
      return { id: 'pages.console.miniCode.error.notFound' };
    case 'wechat_unavailable':
      return { id: 'pages.console.miniCode.error.unavailable' };
    case 'wechat_upstream_error':
      return { id: 'pages.console.miniCode.error.upstream' };
    default:
  }
  switch (status) {
    case 404:
      return { id: 'pages.console.miniCode.error.notFound' };
    case 503:
      return { id: 'pages.console.miniCode.error.unavailable' };
    case 502:
      return { id: 'pages.console.miniCode.error.upstream' };
    case 0:
      return { id: 'pages.ops.devices.error.network' };
    default:
      return {
        id: 'pages.console.miniCode.error.unknown',
        detail: error ?? '',
      };
  }
}
