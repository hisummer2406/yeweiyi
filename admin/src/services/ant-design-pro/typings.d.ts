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
   * 注意是 snake_case，且没有客户归属字段；时间为 RFC3339 字符串，未发生过则为 null。
   */
  type DeviceRow = {
    device_name: string;
    is_online: boolean;
    last_online_at: string | null;
    last_offline_at: string | null;
    updated_at: string | null;
  };

  /** GET /api/customers 的客户（internal/api/admin.go customerResponse） */
  type Customer = {
    id: number;
    name: string;
    createdAt: string;
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
