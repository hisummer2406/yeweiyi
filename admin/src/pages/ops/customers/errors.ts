import { describeRegisterError, type ErrorMessage } from '../devices/errors';

/**
 * 重命名客户（PUT /api/customers/:id）：409 名称已存在（默认客户的 409 不会出现，界面不给它这个操作），
 * 404 客户已被删除，400 名称不合法，其余同登记。按状态码判断，不解析后端文案。
 */
export function describeRenameError(
  status: number,
  error?: string,
): ErrorMessage {
  switch (status) {
    case 409:
      return { id: 'pages.ops.devices.customer.error.exists' };
    case 404:
      return { id: 'pages.ops.customers.error.notFound' };
    case 400:
      return {
        id: 'pages.ops.devices.customer.error.invalid',
        detail: error ?? '',
      };
    default:
      return describeRegisterError(status, error);
  }
}

/** 删除客户（DELETE /api/customers/:id）：409 客户下还有账号、设备或历史记录，404 客户已不存在。 */
export function describeDeleteError(
  status: number,
  error?: string,
): ErrorMessage {
  switch (status) {
    case 409:
      return { id: 'pages.ops.customers.error.inUse' };
    case 404:
      return { id: 'pages.ops.customers.error.notFound' };
    default:
      return describeRegisterError(status, error);
  }
}

/** 云端把名为 default 的客户当作内置客户：不能改名也不能删。 */
export const isDefaultCustomer = (c: API.Customer) => c.name === 'default';
