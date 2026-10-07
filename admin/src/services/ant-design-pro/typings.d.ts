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

  /**
   * GET /api/devices 的行（yeweiyi-cloud internal/api/devices.go deviceResponse）。
   * 注意是 snake_case；时间为 RFC3339 字符串，未发生过则为 null。
   * customer_id 只有平台管理员的行才有：null 为未分配；字段缺失说明云端还没带上它（旧版本），
   * 此时只能显示「未知」，不能当成未分配。
   */
  type DeviceRow = {
    device_name: string;
    is_online: boolean;
    last_online_at: string | null;
    last_offline_at: string | null;
    updated_at: string | null;
    customer_id?: number | null;
  };

  /** PUT /api/devices/:device/customer 响应：changed=false 表示本来就属于该客户 */
  type AssignDeviceResult = {
    deviceName: string;
    customerId: number;
    changed: boolean;
    bindingsCleared: number;
  };

  /** GET /api/customers 的客户（internal/api/admin.go customerResponse） */
  type Customer = {
    id: number;
    name: string;
    createdAt: string;
  };

  type UserRole = 'platform_admin' | 'customer_admin' | 'customer_user';

  /** GET /api/users 的账号（internal/api/admin.go userResponse，不含密码） */
  type AdminUser = {
    id: number;
    username: string;
    role: UserRole;
    customerId: number | null;
    disabled: boolean;
    disabledAt?: string;
    createdAt: string;
  };

  /** POST /api/users 请求体：platform_admin 不带 customerId，其余角色必填 */
  type CreateUserParams = {
    username: string;
    password: string;
    role: UserRole;
    customerId?: number;
  };

  /** PUT /api/users/:id 请求体：disabled 和 password 至少给一个 */
  type UpdateUserParams = {
    disabled?: boolean;
    password?: string;
  };

  /** POST /api/devices 请求体：deviceName 为铭牌完整出厂编号（按字符串，保留前导零） */
  type RegisterDeviceParams = {
    deviceName: string;
    customerId: number;
  };

  /** POST /api/devices 响应：201 首次登记 created=true，200 重复登记 created=false */
  type RegisterDeviceResult = {
    deviceName: string;
    customerId: number;
    created: boolean;
  };

  /** 后端失败响应体：HTTP 状态码 + {error} */
  type ErrorBody = {
    error?: string;
  };

  type TankAlarmState = 'NORMAL' | 'ALARM' | 'FAULT';

  /** telemetry/latest 的一个罐；tankShape 缺失表示未知 */
  type Tank = {
    index: number;
    oilType: string;
    oilLevelMm: number;
    waterLevelMm: number;
    tempC: number;
    oilVolumeL: number;
    waterVolumeL: number;
    oilWeightKg: number;
    nullVolumeL: number;
    oilRatioPct: number;
    waterRatioPct: number;
    alarmState: TankAlarmState | string;
    probeConnected: boolean;
    tankShape?: string;
  };

  /** GET /api/devices/:device/telemetry/latest 成功响应 */
  type LatestTelemetry = {
    deviceName: string;
    ts: string;
    snapshotSeq: number;
    tanks: Tank[];
  };
}
