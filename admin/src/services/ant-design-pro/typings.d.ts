// @ts-ignore
/* eslint-disable */

declare namespace API {
  /** yeweiyi-cloud 的三种角色，见 internal/control 与 internal/api/auth.go */
  type Role = 'platform_admin' | 'customer_admin' | 'customer_user';

  /**
   * 从 JWT claims 解码得到（uid / sub / role），仅用于界面可见性，不是授权依据。
   * access 解码时原样保留；未知角色由 access.ts 判为无权限。
   */
  type CurrentUser = {
    userId: number;
    username: string;
    access: Role;
  };

  /** POST /api/login 请求体 */
  type LoginParams = {
    username: string;
    password: string;
  };

  /** POST /api/login 成功响应 */
  type LoginResult = {
    token: string;
  };

  /** 后端失败响应体：HTTP 状态码 + {error} */
  type ErrorBody = {
    error?: string;
  };
}
