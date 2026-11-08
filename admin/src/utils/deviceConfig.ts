/**
 * 远程配置（cloud#3）的纯函数：字段表、改动差异、本地预检、命令阶段、回显判定、错误归类。
 * 范围与 cloud/internal/protocol/config_args.go 一致，但云端与设备才是裁决者——这里只为了早点提示。
 * 契约：cloud/docs/config-command-contract.md、cloud/docs/tank-settings-contract.md。
 */

export type FieldKind = 'int' | 'number' | 'oil';

export type FieldDef<K extends string = string> = {
  key: K;
  /** 文案 id 后缀：pages.console.settings.field.<key> */
  unit?: 'mm' | '℃' | 'L' | 's';
  kind: FieldKind;
  min: number;
  max: number;
  /** false = 只读（云端拒绝远程修改，或单位未确认） */
  editable: boolean;
};

const INT_MAX = 2147483647;

type TankKey = Exclude<
  keyof API.TankSettings,
  'tank' | 'reportedAt' | 'oilType'
>;

/** 罐设置字段（显示顺序）。密度：单位未与设备对齐，本期只读（用户 2026-11-08 决定）。 */
export const TANK_FIELDS: FieldDef<TankKey>[] = [
  {
    key: 'alarmHighHighLevelMm',
    unit: 'mm',
    kind: 'int',
    min: 0,
    max: INT_MAX,
    editable: true,
  },
  {
    key: 'alarmHighLevelMm',
    unit: 'mm',
    kind: 'int',
    min: 0,
    max: INT_MAX,
    editable: true,
  },
  {
    key: 'alarmLowLevelMm',
    unit: 'mm',
    kind: 'int',
    min: 0,
    max: INT_MAX,
    editable: true,
  },
  {
    key: 'alarmLowLowLevelMm',
    unit: 'mm',
    kind: 'int',
    min: 0,
    max: INT_MAX,
    editable: true,
  },
  {
    key: 'alarmHighWaterMm',
    unit: 'mm',
    kind: 'int',
    min: 0,
    max: INT_MAX,
    editable: true,
  },
  {
    key: 'alarmHighTempC',
    unit: '℃',
    kind: 'int',
    min: -50,
    max: 100,
    editable: true,
  },
  {
    key: 'levelCorrectionMm',
    unit: 'mm',
    kind: 'number',
    min: -100,
    max: 100,
    editable: true,
  },
  {
    key: 'waterCorrectionMm',
    unit: 'mm',
    kind: 'number',
    min: -100,
    max: 100,
    editable: true,
  },
  {
    key: 'tempCorrectionC',
    unit: '℃',
    kind: 'number',
    min: -10,
    max: 10,
    editable: true,
  },
  { key: 'oilTypeIndex', kind: 'oil', min: 0, max: 39, editable: true },
  {
    key: 'capacityL',
    unit: 'L',
    kind: 'int',
    min: 0,
    max: 1_000_000,
    editable: true,
  },
  {
    key: 'tankHeightMm',
    unit: 'mm',
    kind: 'int',
    min: 0,
    max: INT_MAX,
    editable: false,
  },
  { key: 'tankShape', kind: 'int', min: 0, max: 1, editable: false },
  {
    key: 'sensorLengthMm',
    unit: 'mm',
    kind: 'number',
    min: 0,
    max: INT_MAX,
    editable: false,
  },
  { key: 'density', kind: 'number', min: 0, max: 2, editable: false },
  {
    key: 'alarmLowTempC',
    unit: '℃',
    kind: 'int',
    min: -50,
    max: 100,
    editable: false,
  },
];

type DeviceKey = Exclude<keyof API.DeviceSettings, 'deviceName' | 'reportedAt'>;

export const DEVICE_FIELDS: FieldDef<DeviceKey>[] = [
  {
    key: 'inStockHeightThresholdMm',
    unit: 'mm',
    kind: 'int',
    min: 1,
    max: 10000,
    editable: true,
  },
  {
    key: 'inStockTimeThresholdSec',
    unit: 's',
    kind: 'int',
    min: 1,
    max: 86400,
    editable: true,
  },
  {
    key: 'outStockHeightThresholdMm',
    unit: 'mm',
    kind: 'int',
    min: 1,
    max: 10000,
    editable: true,
  },
  {
    key: 'outStockTimeThresholdSec',
    unit: 's',
    kind: 'int',
    min: 1,
    max: 86400,
    editable: true,
  },
];

/** 表单草稿：键 → 用户输入的数（undefined/null = 没填） */
export type Draft = Record<string, number | null | undefined>;

/**
 * 只取改动过的可编辑字段。草稿没填（undefined/null）不算改动；当前值是 null（未上报）而草稿填了，算改动。
 */
export function changedArgs(
  fields: FieldDef[],
  current: Record<string, unknown> | null | undefined,
  draft: Draft,
): Record<string, number> {
  const out: Record<string, number> = {};
  for (const f of fields) {
    if (!f.editable) continue;
    const v = draft[f.key];
    if (typeof v !== 'number' || !Number.isFinite(v)) continue;
    const cur = current?.[f.key];
    if (typeof cur === 'number' && cur === v) continue;
    out[f.key] = v;
  }
  return out;
}

export type FieldProblem = {
  field: string;
  code: 'range' | 'integer' | 'order';
};

const LEVEL_ORDER = [
  'alarmLowLowLevelMm',
  'alarmLowLevelMm',
  'alarmHighLevelMm',
  'alarmHighHighLevelMm',
] as const;

/**
 * 本地预检：每个改动的范围/整数，以及四级液位顺序（当前值 + 改动合并后，非 0 的必须 低低 < 低 < 高 < 高高；0 = 不启用）。
 * 只看本次改动涉及的字段：旧值本身不合规而这次没碰它们，不拦（云端规则相同）。
 */
export function checkArgs(
  fields: FieldDef[],
  current: Record<string, unknown> | null | undefined,
  args: Record<string, number>,
): FieldProblem[] {
  const problems: FieldProblem[] = [];
  for (const f of fields) {
    const v = args[f.key];
    if (v === undefined) continue;
    if ((f.kind === 'int' || f.kind === 'oil') && !Number.isInteger(v)) {
      problems.push({ field: f.key, code: 'integer' });
    } else if (v < f.min || v > f.max) {
      problems.push({ field: f.key, code: 'range' });
    }
  }
  if (LEVEL_ORDER.some((k) => k in args)) {
    const merged = LEVEL_ORDER.map((k) => {
      const v = k in args ? args[k] : current?.[k];
      return typeof v === 'number' ? v : 0;
    });
    const active = merged
      .map((v, i) => ({ v, k: LEVEL_ORDER[i] }))
      .filter((x) => x.v !== 0);
    for (let i = 1; i < active.length; i++) {
      if (active[i].v <= active[i - 1].v) {
        const k = active[i].k in args ? active[i].k : active[i - 1].k;
        problems.push({ field: k, code: 'order' });
        break;
      }
    }
  }
  return problems;
}

/** 液位阈值超过罐高：只提示（"≤ 罐高"由设备裁决） */
export function aboveTankHeight(
  tank: API.TankSettings | undefined,
  args: Record<string, number>,
): string[] {
  const h = tank?.tankHeightMm;
  if (typeof h !== 'number' || h <= 0) return [];
  return [...LEVEL_ORDER, 'alarmHighWaterMm'].filter(
    (k) => typeof args[k] === 'number' && args[k] > h,
  );
}

/** 本次确认的提交编号（cloud 契约 §1：1–64 个 [A-Za-z0-9_-]）。同一次确认的重试必须复用。 */
export function newRequestId(now = Date.now(), rand = Math.random): string {
  const r = Math.floor(rand() * 36 ** 8)
    .toString(36)
    .padStart(8, '0');
  return `adm-${now.toString(36)}-${r}`;
}

export type CommandPhase =
  | 'accepted' // 已受理，设备尚未确认收到
  | 'received' // 设备已接收，等待执行结果
  | 'executing'
  | 'succeeded'
  | 'failed'
  | 'rejected'
  | 'expired'
  | 'unknown';

export function commandPhase(
  cmd: Pick<API.ControlCommand, 'status'>,
): CommandPhase {
  switch (cmd.status) {
    case 'pending':
    case 'sent':
      return 'accepted';
    case 'received':
    case 'executing':
    case 'succeeded':
    case 'failed':
    case 'rejected':
    case 'expired':
      return cmd.status;
    default:
      return 'unknown';
  }
}

export type EchoState = 'matched' | 'waiting' | 'mismatch';

/**
 * 配置命令成功后，读回的镜像是否已反映提交值。镜像的 reportedAt 早于命令下发时刻 = 还是旧回报（waiting）；
 * 新回报且提交字段全部相等 = matched；新回报但有字段不等 = mismatch（可能被现场覆盖，或设备换算/取整）。
 * "已一致"只说明读到了相同的回报，不证明一定由本命令造成（没有配置版本）。
 */
export function echoState(
  mirror:
    | (Record<string, unknown> & { reportedAt?: string })
    | null
    | undefined,
  args: Record<string, unknown>,
  issuedAt: string | undefined,
): EchoState {
  if (!mirror?.reportedAt) return 'waiting';
  const reported = Date.parse(mirror.reportedAt);
  const issued = issuedAt ? Date.parse(issuedAt) : Number.NaN;
  if (
    !Number.isFinite(reported) ||
    (Number.isFinite(issued) && reported < issued)
  ) {
    return 'waiting';
  }
  for (const [k, v] of Object.entries(args)) {
    if (k === 'tank') continue;
    if (mirror[k] !== v) return 'mismatch';
  }
  return 'matched';
}

/** 配置提交失败的归类（按 code，不显示云端 error 原文）。返回文案 id 后缀。 */
export type SubmitErrorKind =
  | 'deploymentDisabled'
  | 'deviceDisabled'
  | 'stationChanged'
  | 'inFlight'
  | 'requestIdReused'
  | 'invalidArgs'
  | 'unconfirmed'
  | 'forbidden'
  | 'purging'
  | 'unavailable'
  | 'other';

export function classifySubmitError(err: {
  status: number;
  code?: string;
  data?: Record<string, unknown>;
}): SubmitErrorKind {
  switch (err.code) {
    case 'config_commands_disabled':
      return err.data?.scope === 'device'
        ? 'deviceDisabled'
        : 'deploymentDisabled';
    case 'device_station_changed':
      return 'stationChanged';
    case 'config_command_in_flight':
      return 'inFlight';
    case 'request_id_reused':
      return 'requestIdReused';
    case 'invalid_args':
      return 'invalidArgs';
    case 'command_delivery_unconfirmed':
      return 'unconfirmed';
    case 'device_purge_in_progress':
      return 'purging';
  }
  if (err.status === 0) return 'unconfirmed';
  if (err.status === 403) return 'forbidden';
  if (err.status === 503) return 'unavailable';
  return 'other';
}

/** 命令的 configTarget（tank:N / device）显示用 */
export function targetTank(
  target: string | null | undefined,
): number | undefined {
  const m = /^tank:(\d+)$/.exec(target ?? '');
  return m ? Number(m[1]) : undefined;
}
