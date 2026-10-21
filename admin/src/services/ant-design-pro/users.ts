import { request } from '@umijs/max';
import { toApiError } from './apiError';

/** GET /api/users：平台管理员看到全部账号，响应 {users}（internal/api/admin.go）。 */
export async function listUsers(): Promise<API.AdminUser[]> {
  const res = await request<{ users: API.AdminUser[] }>('/api/users', {
    method: 'GET',
  });
  return res.users;
}

/**
 * POST /api/users：创建账号，成功 201。用户名已存在 409，参数不合法（角色、密码 8-72 字节、
 * customerId 缺失或不存在）400。失败抛 ApiError，提示由调用方给出，所以跳过全局错误提示
 * （401 在 toApiError 里转交）。
 */
export async function createUser(
  body: API.CreateUserParams,
): Promise<API.AdminUser> {
  try {
    return await request<API.AdminUser>('/api/users', {
      method: 'POST',
      data: body,
      skipErrorHandler: true,
    });
  } catch (err) {
    throw toApiError(err);
  }
}

/** PUT /api/users/:id：停用/启用、重置密码和/或修改手机号。不能停用自己（400），账号不存在 404，手机号已被占用 409 `phone_exists`。 */
export async function updateUser(
  id: number,
  body: API.UpdateUserParams,
): Promise<API.AdminUser> {
  try {
    return await request<API.AdminUser>(`/api/users/${id}`, {
      method: 'PUT',
      data: body,
      skipErrorHandler: true,
    });
  } catch (err) {
    throw toApiError(err);
  }
}

/** PUT /api/users/:id/customer：把账号换到另一个客户，同时清掉该账号已有的设备绑定。 */
export async function moveUser(
  id: number,
  customerId: number,
): Promise<API.AdminUser> {
  try {
    return await request<API.AdminUser>(`/api/users/${id}/customer`, {
      method: 'PUT',
      data: { customerId },
      skipErrorHandler: true,
    });
  } catch (err) {
    throw toApiError(err);
  }
}
