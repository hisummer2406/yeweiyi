/**
 * 远程配置（cloud#3）的纯函数：字段表、改动差异、本地预检、命令阶段、回显判定、错误归类。
 * 范围与 cloud/internal/protocol/config_args.go 一致，但云端与设备才是裁决者——这里只为了早点提示。
 * 契约：cloud/docs/config-command-contract.md、cloud/docs/tank-settings-contract.md。
 */

import { isPaletteColor } from './oilColor';

export type FieldKind = 'int' | 'number' | 'oil' | 'color';

export type FieldDef<K extends string = string> = {
  key: K;
  /** 文案 id 后缀：pages.console.settings.field.<key> */
  unit?: 'mm' | '℃' | 'L' | 's' | 'g/cm³';
  kind: FieldKind;
  min: number;
  max: number;
  /** true = min 本身不允许（密度 > 0） */
  exclMin?: boolean;
  /** false = 只读（云端拒绝远程修改，或单位未确认） */
  editable: boolean;
};

const INT_MAX = 2147483647;

type TankKey = Exclude<
  keyof API.TankSettings,
  'tank' | 'reportedAt' | 'oilType'
>;

/** 罐设置字段（显示顺序）。密度单位 g/cm³（2026-11-14 核实设备按 升×密度=千克 计算），可写 0<v≤2；颜色只能选色板。三项修正不限范围（#28，用户决定）。 */
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
    min: -Infinity,
    max: Infinity,
    editable: true,
  },
  {
    key: 'waterCorrectionMm',
    unit: 'mm',
    kind: 'number',
    min: -Infinity,
    max: Infinity,
    editable: true,
  },
  {
    key: 'tempCorrectionC',
    unit: '℃',
    kind: 'number',
    min: -Infinity,
    max: Infinity,
    editable: true,
  },
  { key: 'oilTypeIndex', kind: 'oil', min: 0, max: 39, editable: true },
  { key: 'oilColor', kind: 'color', min: 0, max: 0xffffff, editable: true },
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
  {
    key: 'density',
    unit: 'g/cm³',
    kind: 'number',
    min: 0,
    max: 2,
    exclMin: true,
    editable: true,
  },
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

/** 可负的小数字段（三项修正）旁边放正负号切换：部分手机键盘打不出负号（#30）。 */
export function hasSignToggle(f: FieldDef): boolean {
  return f.kind === 'number' && f.min < 0;
}

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
  code: 'range' | 'integer' | 'order' | 'color';
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
    if (f.kind === 'color') {
      if (!isPaletteColor(v)) problems.push({ field: f.key, code: 'color' });
    } else if ((f.kind === 'int' || f.kind === 'oil') && !Number.isInteger(v)) {
      problems.push({ field: f.key, code: 'integer' });
    } else if (v < f.min || v > f.max || (f.exclMin && v <= f.min)) {
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

/** 回报的密度大于 2：多半是按设备旧标注 kg/m³ 填的（如 840），只提示核对，不改值 */
export function densityLooksOff(v: number | null | undefined): boolean {
  return typeof v === 'number' && v > 2;
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
 * 配置命令成功后，读回的镜像是否已反映提交值。镜像的 reportedAt 不晚于命令完成时刻 = 还是旧回报（waiting，
 * 契约 §6.5 要求「晚于 finishedAt」，同一时刻也不算；与小程序一致）；
 * 新回报且提交字段全部相等 = matched；新回报但有字段不等 = mismatch（可能被现场覆盖，或设备换算/取整）。
 * "已一致"只说明读到了相同的回报，不证明一定由本命令造成（没有配置版本）。
 */
export function echoState(
  mirror:
    | (Record<string, unknown> & { reportedAt?: string })
    | null
    | undefined,
  args: Record<string, unknown>,
  finishedAt: string | undefined,
): EchoState {
  if (!mirror?.reportedAt) return 'waiting';
  const reported = Date.parse(mirror.reportedAt);
  const finished = finishedAt ? Date.parse(finishedAt) : Number.NaN;
  if (
    !Number.isFinite(reported) ||
    !Number.isFinite(finished) ||
    reported <= finished
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
  | 'mediaNotSelectable'
  | 'mediaSyncUnsupported'
  | 'mediaSlotsExhausted'
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
    case 'media_not_selectable':
    case 'media_id_required':
      return 'mediaNotSelectable';
    case 'media_sync_unsupported':
      return 'mediaSyncUnsupported';
    case 'media_slots_exhausted':
      return 'mediaSlotsExhausted';
  }
  if (err.status === 0) return 'unconfirmed';
  if (err.status === 403) return 'forbidden';
  if (err.status === 503) return 'unavailable';
  return 'other';
}

/**
 * 重发一份未确认的旧快照（同一 requestId）失败后，这份快照是否已了结（#18 F6）。
 * 云端先校验角色/总闸/参数，再按 requestId 重放，之后才复核油站与在途互斥。所以只有重放之后的结论
 * （油站已变、同目标被别的命令占用、编号被别的内容用过）以及参数本身不合法（原提交同样不可能被受理）
 * 能说明这份快照没有被受理；超时、总闸关闭、403、5xx 等仍是未知，保留快照。
 */
export function pendingSettledBy(kind: SubmitErrorKind): boolean {
  return (
    kind === 'stationChanged' ||
    kind === 'inFlight' ||
    kind === 'requestIdReused' ||
    kind === 'invalidArgs' ||
    kind === 'mediaNotSelectable' ||
    kind === 'mediaSyncUnsupported' ||
    kind === 'mediaSlotsExhausted'
  );
}

/**
 * 介质按 mediaId 提交，设备回报的是云端换算出的 oilTypeIndex：回显要按命令实际下发的编号核对。
 * 名称字段设备不回报，不参与比较。
 */
export function trackedArgs(
  command: Pick<API.ControlCommand, 'args'>,
  args: Record<string, unknown>,
): Record<string, unknown> {
  if (!('mediaId' in args)) return args;
  const { mediaId: _mediaId, ...rest } = args;
  const index = command.args?.oilTypeIndex;
  return typeof index === 'number' ? { ...rest, oilTypeIndex: index } : rest;
}

/** 命令的 configTarget（tank:N / device）显示用 */
export function targetTank(
  target: string | null | undefined,
): number | undefined {
  const m = /^tank:(\d+)$/.exec(target ?? '');
  return m ? Number(m[1]) : undefined;
}
