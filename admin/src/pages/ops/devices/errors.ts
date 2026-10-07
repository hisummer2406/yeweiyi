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
