import { request } from '@umijs/max';
import { toApiError } from './apiError';

/** GET /api/customers：仅平台管理员，响应 {customers}（internal/api/admin.go）。 */
export async function listCustomers(): Promise<API.Customer[]> {
  const res = await request<{ customers: API.Customer[] }>('/api/customers', {
    method: 'GET',
  });
  return res.customers;
}

/**
 * POST /api/customers：创建客户，成功 201 返回客户；名称冲突 409，名称不合法 400。失败抛 ApiError，
 * 提示由调用方给出，所以跳过全局错误提示（401 在 toApiError 里转交）。
 */
export async function createCustomer(body: {
  name: string;
}): Promise<API.Customer> {
  try {
    return await request<API.Customer>('/api/customers', {
      method: 'POST',
      data: body,
      skipErrorHandler: true,
    });
  } catch (err) {
    throw toApiError(err);
  }
}
