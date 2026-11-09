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

  /**
   * 设备彻底删除任务（yeweiyi-cloud docs/device-purge-contract.md §2.3）。status 只有 running / failed / completed，
   * completed 才算删完；step 是下一个要做（failed 时为失败）的步骤。counts 只有数字，随步骤逐项出现。
   */
  type DevicePurge = {
    id: number;
    deviceName: string;
    status: 'running' | 'failed' | 'completed';
    step: 'thingsboard' | 'business' | 'verify' | 'done';
    error?: string;
    counts: Record<string, number>;
    requestedBy: { id: number | null; username: string };
    reason: string;
    customerId: number | null;
    stationId: number | null;
    startedAt: string;
    finishedAt: string | null;
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

  /**
   * PUT /api/users/:id 请求体：disabled、password、phone 至少给一个。
   * phone 仅平台管理员可改，且只对手机号账号（客户管理员/员工）有效；云端接口为 yeweiyi-admin 约定的契约，联调前以 mock 测试为准。
   */
  type UpdateUserParams = {
    disabled?: boolean;
    password?: string;
    phone?: string;
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
    /** 可选罐号筛选（yeweiyi-cloud docs/records-filter-contract.md，#84）：服务端先筛选后分页 */
    tank?: number;
    /** 可选方向筛选，仅出入库 */
    direction?: 'IN' | 'OUT';
    /** 每页条数与偏移，分页在云端做 */
    limit: number;
    offset: number;
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
    /** 报警发生时的油品显示名与设备油品表下标（cloud#85）；设备级报警与旧记录为 null/缺失 */
    oilType?: string | null;
    oilTypeIndex?: number | null;
  };

  /** listAlarms 的结果：partial 来自响应头 X-Records-Partial */
  type AlarmList = {
    rows: Alarm[];
    partial: boolean;
    /** 这一页之后还有记录（云端报警接口没有该字段，由前端多要一条判断） */
    truncated: boolean;
  };

  /** GET /api/oil-in-out 的一行（internal/api/stock.go stockEventResponse） */
  type StockEvent = {
    eventId: string;
    tank: number;
    direction: 'IN' | 'OUT' | string;
    oilType?: string;
    source: 'AUTO' | 'MANUAL' | string;
    /** device = 设备上报（含设备端补录），cloud = 云端补录（cloud#69，只有它能撤销）；旧云端没有该字段 */
    origin?: 'device' | 'cloud' | string;
    beginTime: string;
    endTime: string;
    /** 云端补录只知道前高度和升数，其余量为 null（不是 0） */
    beginLevelMm: number | null;
    endLevelMm: number | null;
    beginVolumeL: number | null;
    endVolumeL: number | null;
    beginTempC: number | null;
    endTempC: number | null;
    beginWeightKg: number | null;
    endWeightKg: number | null;
    netVolumeL: number;
    duringDeliveryVolumeL?: number;
    deliveredVolumeL: number;
    /** 设备按记录关闭时刻密度算的交付质量（cloud#85）；旧记录 null，不能用重量差代替 */
    deliveredWeightKg?: number | null;
    /** 设备油品表下标（cloud#85）；1–17 按界面语言显示，其余显示 oilType 原文 */
    oilTypeIndex?: number | null;
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

  /**
   * GET /api/shift-records 与 /api/stock-records 的同步状态（yeweiyi-cloud docs/shift-stock-records-contract.md §4）。
   * 只有 SYNCED 且没有行才能说「暂无记录」；不认识的值（更新的云端）按「无法确认」处理。
   */
  type RecordSyncStatus = 'UNSUPPORTED' | 'SYNCING' | 'SYNCED' | string;

  /** 交班/库存记录的响应信封（truncated = 这一页之后还有记录）；字段缺失时 service 层补成安全默认值，syncStatus 缺失保留 undefined（=无法确认） */
  type SyncedRecordList<R> = {
    rows: R[];
    truncated: boolean;
    partial: boolean;
    /** 整机计数：原文无法归属到罐，按罐筛选时也不按罐区分 */
    unparsable: number;
    syncStatus?: RecordSyncStatus;
  };

  /** GET /api/shift-records 的一行：每个 (班, 罐) 一行；缺失值一律 null，显示「—」不显示 0 */
  type ShiftRecord = {
    shiftId: string;
    /** 进行中的班是 OPEN，此时 endTime 与结束、出库各列为 null */
    status: 'OPEN' | 'CLOSED' | string;
    /** 接班时间 */
    beginTime: string | null;
    /** 交班时间 */
    endTime: string | null;
    tank: number;
    oilType: string | null;
    oilTypeIndex: number | null;
    beginLevelMm: number | null;
    beginVolumeL: number | null;
    endLevelMm: number | null;
    endVolumeL: number | null;
    outVolumeL: number | null;
    outWeightKg: number | null;
  };

  /** GET /api/stock-records 的一行：每个 (库存记录, 罐) 一行；液位、水高单位 mm */
  type InventoryRecord = {
    recordId: string;
    time: string | null;
    trigger: string | null;
    tank: number;
    oilType: string | null;
    oilTypeIndex: number | null;
    levelMm: number | null;
    volumeL: number | null;
    waterLevelMm: number | null;
    tempC: number | null;
    weightKg: number | null;
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

  /**
   * 设备上报的罐配置镜像（cloud docs/tank-settings-contract.md）。字段 null = 设备未上报，0 = 设备上报的 0（报警阈值 0 = 不启用）。
   */
  type TankSettings = {
    tank: number;
    reportedAt: string;
    tankHeightMm: number | null;
    sensorLengthMm: number | null;
    tankShape: number | null;
    oilType: string | null;
    oilTypeIndex: number | null;
    alarmHighHighLevelMm: number | null;
    alarmHighLevelMm: number | null;
    alarmLowLevelMm: number | null;
    alarmLowLowLevelMm: number | null;
    alarmHighWaterMm: number | null;
    alarmHighTempC: number | null;
    alarmLowTempC: number | null;
    levelCorrectionMm: number | null;
    waterCorrectionMm: number | null;
    tempCorrectionC: number | null;
    density: number | null;
    capacityL: number | null;
  };

  /** 整机设置镜像（cloud docs/config-command-contract.md §4）；0 = 设备未配置，null = 未上报 */
  type DeviceSettings = {
    deviceName: string;
    reportedAt: string;
    inStockHeightThresholdMm: number | null;
    inStockTimeThresholdSec: number | null;
    outStockHeightThresholdMm: number | null;
    outStockTimeThresholdSec: number | null;
  };

  /** 罐标定表（cloud docs/tank-settings-contract.md「罐体标定表」） */
  type TankCalibration = {
    deviceName: string;
    tank: number;
    reportedAt: string;
    pointCount: number;
    heightMm: number[];
    volumeL: number[];
  };

  type CommandStatus =
    | 'pending'
    | 'sent'
    | 'received'
    | 'executing'
    | 'succeeded'
    | 'failed'
    | 'rejected'
    | 'expired';

  /** 控制命令（cloud internal/api/control.go commandResponse）。terminal 由云端计算，是否继续轮询只看它。 */
  type Medium = {
    id: number;
    name: string;
    nameEn: string | null;
    scope: 'public' | 'customer';
    customerId: number | null;
    builtinIndex: number | null;
    enabled: boolean;
    updatedAt: string;
  };
  type MediumCreate = {
    name: string;
    nameEn?: string;
    scope: 'public' | 'customer';
    customerId?: number;
  };
  type MediumUpdate = { name?: string; nameEn?: string; enabled?: boolean };
  type MediaOptions = {
    public: Medium[];
    customer: Medium[];
    customMediaSync: boolean;
    slotsUsed: number;
    slotsTotal: number;
  };

  type ControlCommand = {
    commandId: string;
    deviceName: string;
    method: string;
    args?: Record<string, unknown>;
    operator: string;
    reason: string;
    status: CommandStatus | string;
    terminal: boolean;
    errorCode?: string;
    errorReason?: string;
    resultDetail?: { tank?: number; updated?: string[]; ignored?: string[] } & Record<string, unknown>;
    issuedAt?: string;
    expiresAt?: string;
    sentAt?: string;
    ackedAt?: string;
    finishedAt?: string;
    requestId?: string | null;
    expectedStationId?: number | null;
    configTarget?: string | null;
  };

}
