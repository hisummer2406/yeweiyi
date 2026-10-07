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

/**
 * PUT /api/customers/:id：重命名，成功 200 返回客户。名称不合法（1-100 字符）400，客户不存在 404，
 * 名称已存在或是默认客户 409。失败抛 ApiError（401 在 toApiError 里转交）。
 */
export async function renameCustomer(
  id: number,
  name: string,
): Promise<API.Customer> {
  try {
    return await request<API.Customer>(`/api/customers/${id}`, {
      method: 'PUT',
      data: { name },
      skipErrorHandler: true,
    });
  } catch (err) {
    throw toApiError(err);
  }
}

/**
 * DELETE /api/customers/:id：真删除，不可恢复，成功 204 无响应体。客户不存在 404；默认客户，或客户下
 * 还有账号、设备、历史记录时 409。失败抛 ApiError（401 在 toApiError 里转交）。
 */
export async function deleteCustomer(id: number): Promise<void> {
  try {
    await request(`/api/customers/${id}`, {
      method: 'DELETE',
      skipErrorHandler: true,
    });
  } catch (err) {
    throw toApiError(err);
  }
}
