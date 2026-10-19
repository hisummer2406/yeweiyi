// @ts-ignore
/* eslint-disable */

declare namespace API {
  /** yeweiyi-cloud 的三种角色，见 internal/control 与 internal/api/auth.go */
  type Role = 'platform_admin' | 'customer_admin' | 'customer_user';

  /**
   * 从 JWT claims 解码得到（uid / role），仅用于界面可见性，不是授权依据。
   * access 解码时原样保留；未知角色由 access.ts 判为无权限。
   * username 只是显示标签：令牌 sub 自 yeweiyi-cloud#59 起是用户 ID，不再是用户名，所以它来自登录响应里的
   * user（平台管理员=用户名，客户管理员=手机号），旧令牌才会取到 sub 里的用户名；取不到时为空串。
   */
  type CurrentUser = {
    userId: number;
    username: string;
    access: Role;
  };

  /**
   * POST /api/login 请求体：username（平台管理员）与 phone（客户管理员）**恰好给一个**，
   * 由调用方按输入内容决定（见 utils/loginIdentifier.ts），云端不替你猜。
   */
  type LoginParams = {
    username?: string;
    phone?: string;
    password: string;
  };

  /** 登录响应与 GET /api/me 里的用户（cloud docs/account-phone-wechat-contract.md §3.1） */
  type SessionUser = {
    id: number;
    role: Role;
    customerId: number | null;
    /** 平台管理员与客户侧用户名账号有值（#77）；手机号账号为 null */
    username: string | null;
    /** 手机号账号有值；用户名账号为 null。本人与平台管理员看到完整号码，其余为 138****0000 */
    phone: string | null;
    disabled: boolean;
  };

  /** POST /api/login 成功响应 */
  type LoginResult = {
    token: string;
    user?: SessionUser;
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
    /** 所属油站；null 为未分组，字段缺失 = 旧版云端 */
    station_id?: number | null;
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
    /** 开通状态（cloud#60）：configuring 配置中 | active 已开通；旧版云端没有该字段 */
    onboardingStatus?: 'configuring' | 'active';
    onboardedAt?: string | null;
  };

  /** 开通还缺的条件（customer-onboarding-contract §2） */
  type OnboardingMissing = 'customer_admin' | 'station' | 'device_in_station';

  /** GET/POST /api/customers/:id/onboarding(/complete) 的响应 */
  type CustomerOnboarding = {
    customerId: number;
    status: 'configuring' | 'active';
    complete: boolean;
    missing: OnboardingMissing[];
    counts: {
      customerAdmins: number;
      stations: number;
      devicesInStation: number;
    };
  };

  /** GET /api/stations 的油站（internal/api/stations.go stationResponse） */
  type Station = {
    id: number;
    customerId: number;
    name: string;
    address: string;
    createdAt: string;
  };

  /** PUT /api/devices/:device/station 响应：changed=false 表示本来就在该油站 */
  type AssignStationResult = {
    deviceName: string;
    stationId: number | null;
    changed: boolean;
  };

  /** POST /api/stations/:id/replace-device 响应；changed=false 表示新设备本来就是该油站当前设备 */
  type ReplaceStationDeviceResult = {
    stationId: number;
    deviceName: string;
    previousDeviceName: string | null;
    changed: boolean;
  };

  /** GET /api/devices/:device/tanks（tank-grants-contract §4）：设备已上报的罐；reported:false = 从未上报 */
  type DeviceTanks = {
    deviceName: string;
    reported: boolean;
    tanks: { index: number }[];
  };

  /** GET /api/users/:id/tank-grants：员工在各设备上被授予的罐 */
  type UserTankGrants = {
    userId: number;
    grants: { deviceName: string; tanks: number[] }[];
  };

  /** PUT /api/users/:id/tank-grants/:device 响应：changed=false 表示集合未变（幂等） */
  type SetTankGrantsResult = {
    userId: number;
    deviceName: string;
    tanks: number[];
    changed: boolean;
  };

  type UserRole = 'platform_admin' | 'customer_admin' | 'customer_user';

  /** GET /api/users 的账号（internal/api/admin.go userResponse，不含密码） */
  type AdminUser = {
    id: number;
    /** 平台管理员与客户侧用户名账号有值；手机号账号为 null */
    username: string | null;
    phone: string | null;
    role: UserRole;
    customerId: number | null;
    disabled: boolean;
    disabledAt?: string;
    createdAt: string;
  };

  /**
   * POST /api/users 请求体，按角色（cloud 契约 §4.5、§11.3），只有平台管理员能调：
   * - platform_admin：username + password，不带 phone / customerId；
   * - 客户侧账号（customer_admin / customer_user）：customerId，**phone 与 username 恰好给一个**：
   *   - username 账号：password 必填；
   *   - phone 账号：customer_admin 必填 password，customer_user 的 password 可选（不设则只能微信）。
   */
  type CreateUserParams =
    | { role: 'platform_admin'; username: string; password: string }
    | {
        role: 'customer_admin';
        phone: string;
        password: string;
        customerId: number;
      }
    | {
        role: 'customer_user';
        phone: string;
        password?: string;
        customerId: number;
      }
    | {
        role: 'customer_admin' | 'customer_user';
        username: string;
        password: string;
        customerId: number;
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

  /** 记录查询的公共参数（/api/alarms、/api/oil-in-out），时间为 RFC3339 */
  type RecordsQuery = {
    device: string;
    beginTime: string;
    endTime: string;
  };

  /** GET /api/alarms 的一行（internal/api/alarms.go alarmResponse）；一次状态变化一行 */
  type Alarm = {
    /** 云端合成的幂等 id 不是设备的 id，此时为空串 */
    alarmId: string;
    tank: number;
    type: string;
    severity: string;
    status: string;
    text: string;
    value?: number;
    time: string;
  };

  /** listAlarms 的结果：partial 来自响应头 X-Records-Partial */
  type AlarmList = {
    rows: Alarm[];
    partial: boolean;
  };

  /** GET /api/oil-in-out 的一行（internal/api/stock.go stockEventResponse） */
  type StockEvent = {
    eventId: string;
    tank: number;
    direction: 'IN' | 'OUT' | string;
    oilType?: string;
    source: 'AUTO' | 'MANUAL' | string;
    beginTime: string;
    endTime: string;
    beginLevelMm: number;
    endLevelMm: number;
    beginVolumeL: number;
    endVolumeL: number;
    beginTempC: number;
    endTempC: number;
    beginWeightKg: number;
    endWeightKg: number;
    netVolumeL: number;
    duringDeliveryVolumeL?: number;
    deliveredVolumeL: number;
    time: string;
  };

  /** 出入库 502（设备固件未升级）时云端给出的说明，原样展示 */
  type StockDegraded = {
    code: string;
    error?: string;
    device?: string;
    sample?: string;
  };

  /**
   * listStockEvents 的结果。200 与「502 且带 code」都落到这里：502 不是通用失败，而是带
   * 已读到的 rows 和计数的降级结果，degraded 有值即表示这次是 502。
   */
  type StockList = {
    rows: StockEvent[];
    parsed: number;
    unparsable: number;
    legacySkipped: number;
    invalidSource: number;
    folded: number;
    truncated: boolean;
    partial: boolean;
    degraded?: StockDegraded;
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
