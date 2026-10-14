import { request } from '@umijs/max';
import { toApiError } from './apiError';

/**
 * 员工按罐授权（yeweiyi-cloud docs/tank-grants-contract.md v1.0，cloud#63）。授权粒度是
 * (用户, device_name, 罐号)，不是油站、不是整机；服务端是权限边界，页面隐藏不算。
 * 这些请求自己处理错误提示（skipErrorHandler），鉴权失败交 toApiError 转交全局；
 * 对象级拒绝是 404/409（带 code），不会是账号失效的 403 not authorized。
 */

/**
 * GET /api/devices/:device/tanks：设备**已上报**的罐。reported:false = 设备还没有上报过（tanks 为空），
 * 此时不能新增授权（PUT 会 409 tanks_unknown），但可撤销。设备编号在路径里，要转义。
 */
export async function getDeviceTanks(
  device: string,
): Promise<API.DeviceTanks> {
  try {
    return await request<API.DeviceTanks>(
      `/api/devices/${encodeURIComponent(device)}/tanks`,
      { method: 'GET', skipErrorHandler: true },
    );
  } catch (err) {
    throw toApiError(err);
  }
}

/**
 * GET /api/users/:id/tank-grants：该员工在各设备上被授予的罐。目标必须是调用者范围内的员工：
 * 别客户/不存在 404 user_not_found（客户管理员视角二者相同），目标是管理员 409 grant_not_applicable。
 */
export async function listUserTankGrants(
  userId: number,
): Promise<API.UserTankGrants> {
  try {
    return await request<API.UserTankGrants>(`/api/users/${userId}/tank-grants`, {
      method: 'GET',
      skipErrorHandler: true,
    });
  } catch (err) {
    throw toApiError(err);
  }
}

/**
 * PUT /api/users/:id/tank-grants/:device：把该员工在该设备上的授权**整组替换**为 tanks；[] = 撤销该设备全部授权。
 * tanks 键必须存在（这里总会发送）。相同集合重复提交 200 changed:false（幂等）。
 * 400 invalid_request / invalid_tank（罐号不在 1..64 或设备未上报该罐）；409 tanks_unknown（设备从未上报，不能新增）、
 * device_customer_mismatch、grant_not_applicable；404 user_not_found / device_not_found。
 */
export async function setUserTankGrants(
  userId: number,
  device: string,
  tanks: number[],
): Promise<API.SetTankGrantsResult> {
  try {
    return await request<API.SetTankGrantsResult>(
      `/api/users/${userId}/tank-grants/${encodeURIComponent(device)}`,
      { method: 'PUT', data: { tanks }, skipErrorHandler: true },
    );
  } catch (err) {
    throw toApiError(err);
  }
}
