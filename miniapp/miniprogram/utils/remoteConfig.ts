// 远程配置与补录（miniapp#6，总方案 #3）。Pure (no `wx`), checked in Node by `npm run check`.
//
// Contracts: yeweiyi-cloud docs/config-command-contract.md (submission protection),
// docs/tank-settings-contract.md (fields, ranges), docs/stock-manual-entry-contract.md (补录).
// The cloud and the device decide; the checks here only say "no" earlier.

export type SendField = {
  // Key in the page form (储罐设置 / 参数修正 field keys, see editFields)
  form: string;
  // Field name on the wire / in the tank-settings mirror
  cloud: string;
  integer: boolean;
  min: number;
  max: number;
  // min itself is not allowed (density > 0)
  exclMin?: boolean;
};

const INT_MAX = 2147483647;

// 储罐设置 → config.setTankSettings. Not here on purpose: height (the cloud refuses it), waterLow (the device
// has no such alarm), tempLow (the device never checks it). Medium (mediaId) and colour (oilColor) are chosen,
// not typed: buildMediumArgs. Density is g/cm³ (= kg/L; 2026-11-14), 0 < v ≤ 2.
export const TANK_SEND_FIELDS: readonly SendField[] = [
  { form: 'highHigh', cloud: 'alarmHighHighLevelMm', integer: true, min: 0, max: INT_MAX },
  { form: 'high', cloud: 'alarmHighLevelMm', integer: true, min: 0, max: INT_MAX },
  { form: 'low', cloud: 'alarmLowLevelMm', integer: true, min: 0, max: INT_MAX },
  { form: 'lowLow', cloud: 'alarmLowLowLevelMm', integer: true, min: 0, max: INT_MAX },
  { form: 'waterHigh', cloud: 'alarmHighWaterMm', integer: true, min: 0, max: INT_MAX },
  { form: 'tempHigh', cloud: 'alarmHighTempC', integer: true, min: -50, max: 100 },
  { form: 'capacity', cloud: 'capacityL', integer: true, min: 0, max: 1_000_000 },
  { form: 'density', cloud: 'density', integer: false, min: 0, max: 2, exclMin: true },
];

// Draft fields shown next to the device's value but never sent.
export const TANK_DISPLAY_ONLY: Readonly<Record<string, string>> = {
  height: 'tankHeightMm',
  tempLow: 'alarmLowTempC',
};

// 参数修正 → config.setTankSettings. No range limit on purpose (#28): the device just adds them to the reading.
export const CORRECTION_SEND_FIELDS: readonly SendField[] = [
  { form: 'levelOffset', cloud: 'levelCorrectionMm', integer: false, min: -Infinity, max: Infinity },
  { form: 'waterOffset', cloud: 'waterCorrectionMm', integer: false, min: -Infinity, max: Infinity },
  { form: 'tempOffset', cloud: 'tempCorrectionC', integer: false, min: -Infinity, max: Infinity },
];

// ---- 编辑表单字段（Issue #43：罐详情参数页签与设置页共用） ----

export type ParamKind = 'calibration' | 'tank';

export type Unit = 'mm' | 'L' | '℃' | 'g/cm³';

export type EditField = SendField & { unit: Unit; signed: boolean; labelKey: string };

const UNITS: Record<string, Unit> = {
  levelOffset: 'mm',
  waterOffset: 'mm',
  tempOffset: '℃',
  capacity: 'L',
  highHigh: 'mm',
  high: 'mm',
  lowLow: 'mm',
  low: 'mm',
  waterHigh: 'mm',
  tempHigh: '℃',
  density: 'g/cm³',
};

// 储罐设置 order as on the old form (capacity first, then the four levels high → low, water, temperature).
const TANK_ORDER = ['capacity', 'highHigh', 'high', 'lowLow', 'low', 'waterHigh', 'tempHigh', 'density'];

export function editFields(kind: ParamKind): EditField[] {
  if (kind === 'calibration') {
    return CORRECTION_SEND_FIELDS.map((f) => ({ ...f, unit: UNITS[f.form], signed: true, labelKey: `calibration.${f.form}` }));
  }
  return TANK_ORDER.map((k) => TANK_SEND_FIELDS.find((f) => f.form === k) as SendField).map((f) => ({
    ...f,
    unit: UNITS[f.form],
    // Temperatures can be below zero; the digit keyboard has no minus sign on every phone (#30).
    signed: f.min < 0,
    labelKey: `tankCfg.${f.form}`,
  }));
}

// Shown with the device's value, never editable or sent (height: refused by the cloud; low temperature: never
// checked by the device). Low water has no device field at all.
export const TANK_READONLY: readonly { form: string; cloud: string; unit: Unit }[] = [
  { form: 'height', cloud: 'tankHeightMm', unit: 'mm' },
  { form: 'tempLow', cloud: 'alarmLowTempC', unit: '℃' },
];

// The ± button beside a correction: some phone keyboards have no usable minus (#30). It only flips the sign of
// the text — never a step — and '' → '-' lets the user tap first and type after; buildArgs still refuses a bare '-'.
export function toggleSign(text: string): string {
  const t = text.trim();
  return t.startsWith('-') ? t.slice(1) : `-${t}`;
}

const NUMBER = /^-?\d+(\.\d+)?$/;

export type SendProblem = { form: string; reason: 'number' | 'integer' | 'range' | 'order' };

export type BuiltArgs = { args: Record<string, number>; problems: SendProblem[] };

const LEVELS = ['alarmLowLowLevelMm', 'alarmLowLevelMm', 'alarmHighLevelMm', 'alarmHighHighLevelMm'];

// What to send: the filled-in form fields whose value differs from the device's report. An empty field is
// "leave as is", never 0. The 4-level order is checked on report + changes (0 = not enabled), and only when
// a level is part of the change — old values that were already out of order are not ours to refuse.
export function buildArgs(
  fields: readonly SendField[],
  values: Record<string, string>,
  mirror: Record<string, unknown> | null | undefined,
): BuiltArgs {
  const args: Record<string, number> = {};
  const problems: SendProblem[] = [];
  for (const f of fields) {
    const raw = (values[f.form] ?? '').trim();
    if (raw === '') continue;
    if (!NUMBER.test(raw)) {
      problems.push({ form: f.form, reason: 'number' });
      continue;
    }
    const n = Number(raw);
    if (!Number.isFinite(n)) {
      problems.push({ form: f.form, reason: 'number' });
      continue;
    }
    // The device's own value, untouched: not ours to refuse (a density of 840 typed in kg/m³ must not block the
    // rest of the form), and nothing to send.
    if (mirror && mirror[f.cloud] === n) continue;
    if (f.integer && !Number.isInteger(n)) {
      problems.push({ form: f.form, reason: 'integer' });
      continue;
    }
    if (n < f.min || n > f.max || (f.exclMin && n <= f.min)) {
      problems.push({ form: f.form, reason: 'range' });
      continue;
    }
    args[f.cloud] = n;
  }
  if (LEVELS.some((k) => k in args)) {
    const merged = LEVELS.map((k) => {
      const v = k in args ? args[k] : mirror?.[k];
      return { k, v: typeof v === 'number' ? v : 0 };
    }).filter((x) => x.v !== 0);
    for (let i = 1; i < merged.length; i++) {
      if (merged[i].v <= merged[i - 1].v) {
        const k = merged[i].k in args ? merged[i].k : merged[i - 1].k;
        const f = fields.find((x) => x.cloud === k);
        problems.push({ form: f ? f.form : k, reason: 'order' });
        break;
      }
    }
  }
  return { args, problems };
}

// Medium and colour are picked, not typed (2026-11-14): send the medium chosen (the cloud turns mediaId into the
// device's number) and the colour if it differs from the report. Nothing chosen = nothing sent.
export function buildMediumArgs(
  mediaId: number | undefined,
  oilColor: number | undefined,
  mirror: Record<string, unknown> | null | undefined,
): Record<string, number> {
  const out: Record<string, number> = {};
  if (typeof mediaId === 'number') out.mediaId = mediaId;
  if (typeof oilColor === 'number' && mirror?.oilColor !== oilColor) out.oilColor = oilColor;
  return out;
}

// Fields the device left untouched in a succeeded command (cmdResult detail.ignored), e.g. an older firmware.
export function ignoredFields(cmd: { resultDetail?: unknown }): string[] {
  const d = cmd.resultDetail as { ignored?: unknown } | null | undefined;
  return d && Array.isArray(d.ignored) ? d.ignored.filter((x): x is string => typeof x === 'string') : [];
}

// The mirror's value as form text ('' for not reported), for 导入设备值.
export function mirrorToForm(
  fields: readonly SendField[],
  mirror: Record<string, unknown> | null | undefined,
): Record<string, string> {
  const out: Record<string, string> = {};
  for (const f of fields) {
    const v = mirror?.[f.cloud];
    out[f.form] = typeof v === 'number' ? String(v) : '';
  }
  return out;
}

// One submission id per confirmation (cloud contract §1: 1–64 of [A-Za-z0-9_-]); a retry of the same
// confirmation reuses it.
export function newRequestId(prefix = 'mp', now = Date.now(), rand = Math.random): string {
  const r = Math.floor(rand() * 36 ** 8).toString(36).padStart(8, '0');
  return `${prefix}-${now.toString(36)}-${r}`;
}

export type Phase = 'accepted' | 'received' | 'executing' | 'succeeded' | 'failed' | 'rejected' | 'expired' | 'unknown';

export function commandPhase(status: string): Phase {
  if (status === 'pending' || status === 'sent') return 'accepted';
  if (['received', 'executing', 'succeeded', 'failed', 'rejected', 'expired'].includes(status)) return status as Phase;
  return 'unknown';
}

// What the record shows after the status (#42). Only a device refusal or failure has an explanation: the
// device's own reason as is (data, not translated), else the error code's key. A received ACK carries
// "已受理" in errorReason; on an expired/in-flight command that is not an explanation of anything and
// must not be shown ("结果无法确认：已受理" read as if the device had accepted the change).
export const KNOWN_ERROR_CODES = [
  'UNKNOWN_METHOD', 'INVALID_PARAMS', 'EXPIRED', 'INTERLOCK_BLOCKED', 'BUSY', 'NOT_SUPPORTED', 'HARDWARE_FAULT', 'LOCAL_MODE_LOCKED',
];

export type CommandError = { text: string } | { key: string } | null;

export function commandError(status: string, errorCode?: string, errorReason?: string): CommandError {
  if (status !== 'rejected' && status !== 'failed') return null;
  const reason = (errorReason || '').trim();
  if (reason) return { text: reason };
  if (errorCode && KNOWN_ERROR_CODES.includes(errorCode)) return { key: `cmdLog.code.${errorCode}` };
  if (errorCode) return { text: errorCode };
  return null;
}

export type Echo = 'matched' | 'waiting' | 'mismatch';

// After succeeded: only a report strictly later than the command's finishedAt can be the device's answer
// to it (config-command-contract §6.5). Older or same-time is still the old mirror (waiting); a missing or
// unreadable finishedAt cannot be compared, so it stays waiting too (issue #18 F3). A later report with
// every submitted field equal is matched; later but different is mismatch (changed on site, or rounded).
export function echoState(
  mirror: Record<string, unknown> | null | undefined,
  args: Record<string, unknown>,
  finishedAt: string | undefined,
): Echo {
  const reportedAt = mirror ? mirror.reportedAt : undefined;
  if (typeof reportedAt !== 'string' || typeof finishedAt !== 'string') return 'waiting';
  const reported = Date.parse(reportedAt);
  const finished = Date.parse(finishedAt);
  if (!Number.isFinite(reported) || !Number.isFinite(finished) || reported <= finished) return 'waiting';
  for (const k of Object.keys(args)) {
    // The custom medium's names travel with its index (the cloud adds them) but are not mirrored; the index is.
    if (k === 'tank' || k === 'customOilName' || k === 'customOilNameEn') continue;
    if (mirror?.[k] !== args[k]) return 'mismatch';
  }
  return 'matched';
}

export type SubmitError =
  | 'deploymentDisabled'
  | 'deviceDisabled'
  | 'stationChanged'
  | 'inFlight'
  | 'requestIdReused'
  | 'invalidArgs'
  | 'unconfirmed'
  | 'other';

function codeOf(body: unknown): string | undefined {
  const c = body && typeof body === 'object' ? (body as { code?: unknown }).code : undefined;
  return typeof c === 'string' ? c : undefined;
}

// A failed configuration submission, by the cloud's code. Status 0 (no answer, timeout) is "unconfirmed":
// the cloud may have taken it.
export function classifySubmit(status: number, body: unknown): SubmitError {
  switch (codeOf(body)) {
    case 'config_commands_disabled':
      return (body as { scope?: string }).scope === 'device' ? 'deviceDisabled' : 'deploymentDisabled';
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
  }
  return status === 0 ? 'unconfirmed' : 'other';
}

// ---- 补录 (POST /api/devices/:device/stock-events) ----

export type BackfillEntry = { date: string; time: string; beforeLevel: string; volume: string };

export type BackfillProblem = 'tank' | 'time' | 'future' | 'level' | 'volume' | 'reason';

// Local date (YYYY-MM-DD) + time (HH:mm) → RFC 3339 with this phone's offset, e.g. 2026-10-14T08:30:00+08:00.
export function localRfc3339(date: string, time: string, offsetMinutes: number): string | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !/^\d{2}:\d{2}$/.test(time)) return null;
  // getTimezoneOffset is minutes *behind* UTC (UTC+8 → -480)
  const east = -offsetMinutes;
  const sign = east >= 0 ? '+' : '-';
  const abs = Math.abs(east);
  const hh = String(Math.floor(abs / 60)).padStart(2, '0');
  const mm = String(abs % 60).padStart(2, '0');
  return `${date}T${time}:00${sign}${hh}:${mm}`;
}

// The offset (Date#getTimezoneOffset convention, minutes behind UTC) that applied at that local date and time on
// this phone — not today's: under daylight saving a July entry made in November differs by an hour (Codex review,
// PR #15). null for an unparsable date/time.
export function offsetAtLocal(date: string, time: string): number | null {
  const d = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  const t = /^(\d{2}):(\d{2})$/.exec(time);
  if (!d || !t) return null;
  return new Date(Number(d[1]), Number(d[2]) - 1, Number(d[3]), Number(t[1]), Number(t[2])).getTimezoneOffset();
}

// Same entry as typed (for keeping an unresolved submission): every field that goes into the body.
export function backfillFingerprint(tankIndex: number, entry: BackfillEntry, reason: string): string {
  return JSON.stringify([tankIndex, entry.date, entry.time, entry.beforeLevel.trim(), entry.volume.trim(), reason.trim()]);
}

export type BackfillBody = {
  direction: 'IN' | 'OUT';
  tank: number;
  time: string;
  beforeLevelMm: number;
  volumeL: number;
  reason: string;
  requestId: string;
};

// Validates and builds one 补录. The cloud has the last word (e.g. before the device joined the customer).
export function buildBackfill(
  direction: 'IN' | 'OUT',
  tank: number | undefined,
  entry: BackfillEntry,
  reason: string,
  requestId: string,
  now: number,
  offsetMinutes: number,
): { body?: BackfillBody; problem?: BackfillProblem } {
  if (tank === undefined) return { problem: 'tank' };
  const time = localRfc3339(entry.date, entry.time, offsetMinutes);
  if (!time) return { problem: 'time' };
  // One minute of clock skew, as the cloud allows
  if (Date.parse(time) > now + 60_000) return { problem: 'future' };
  const level = entry.beforeLevel.trim();
  if (!NUMBER.test(level) || Number(level) < 0) return { problem: 'level' };
  const volume = entry.volume.trim();
  if (!NUMBER.test(volume) || !(Number(volume) > 0)) return { problem: 'volume' };
  const why = reason.trim();
  if (!why) return { problem: 'reason' };
  return {
    body: {
      direction,
      tank,
      time,
      beforeLevelMm: Number(level),
      volumeL: Number(volume),
      reason: why,
      requestId,
    },
  };
}

// ---- 结果未确认的提交 (issue #18 F2) ----
// A submission whose outcome is unknown (timeout / command_delivery_unconfirmed, lookup found nothing)
// is kept on the phone, per account, per target (device + tank: the cloud's in-flight lock is per tank
// too). Before anything new is sent to that target the kept one is looked up again and, if still not
// found, only resent *as it was* with its own requestId — the cloud replays it if it had taken it.

// Configuration methods the send flow may carry (#49 adds the tank table; absent = config.setTankSettings,
// which is what every snapshot kept before #49 is).
export const SEND_METHODS = ['config.setTankSettings', 'config.setTankTable'] as const;
export type SendMethod = (typeof SEND_METHODS)[number];
export type SendArgs = Record<string, number | string>;

export type PendingSubmit = {
  requestId: string;
  method?: SendMethod;
  device: string;
  stationId: number | null;
  tank: number;
  args: SendArgs;
  reason: string;
  lines: { label: string; before: string; after: string }[];
  savedAt: number;
};

export function pendingStorageKey(userKey: string): string {
  return `cfg-pending:${userKey || 'anon'}`;
}

export function pendingTargetKey(device: string, tank: number): string {
  return `${device}#${tank}`;
}

// Settings carry numbers only; the tank table also carries its shape as text (#49).
function isArgsRecord(v: unknown, method: string | undefined): v is SendArgs {
  const textOk = method === 'config.setTankTable';
  return !!v && typeof v === 'object' &&
    Object.values(v as object).every((x) => (typeof x === 'number' && Number.isFinite(x)) || (textOk && typeof x === 'string'));
}

// Reads one kept submission out of a stored map, dropping anything malformed (an unreadable entry
// cannot be resent safely, and keeping it would block the target for ever).
export function readPending(store: unknown, device: string, tank: number): PendingSubmit | undefined {
  if (!store || typeof store !== 'object') return undefined;
  const p = (store as Record<string, unknown>)[pendingTargetKey(device, tank)] as Partial<PendingSubmit> | undefined;
  if (!p || typeof p !== 'object') return undefined;
  if (typeof p.requestId !== 'string' || !p.requestId) return undefined;
  if (p.device !== device || p.tank !== tank) return undefined;
  if (!(p.stationId === null || (typeof p.stationId === 'number' && Number.isFinite(p.stationId)))) return undefined;
  if (p.method !== undefined && !(SEND_METHODS as readonly string[]).includes(p.method)) return undefined;
  if (!isArgsRecord(p.args, p.method) || typeof p.reason !== 'string' || !Array.isArray(p.lines)) return undefined;
  return p as PendingSubmit;
}

export function withPending(store: unknown, p: PendingSubmit): Record<string, PendingSubmit> {
  const base = store && typeof store === 'object' ? { ...(store as Record<string, PendingSubmit>) } : {};
  base[pendingTargetKey(p.device, p.tank)] = p;
  return base;
}

export function withoutPending(store: unknown, device: string, tank: number): Record<string, PendingSubmit> {
  const base = store && typeof store === 'object' ? { ...(store as Record<string, PendingSubmit>) } : {};
  delete base[pendingTargetKey(device, tank)];
  return base;
}

// After an attempt: keep the snapshot or drop it. A first send that failed with anything but "unconfirmed"
// was not taken (nothing to protect). A retry of a kept snapshot is settled only by an answer given after the
// cloud's requestId replay lookup (station changed, in flight for another command, id used for other
// content) or by args the original could not have passed either; the deployment switch, a 403, a 5xx come
// before that lookup and say nothing about the id, so the snapshot stays (same rule as admin pendingSettledBy).
export function pendingAfter(kind: SubmitError | 'ok' | 'notSent', retry = false): 'keep' | 'clear' {
  if (kind === 'ok' || kind === 'notSent') return 'clear';
  if (kind === 'unconfirmed') return 'keep';
  if (!retry) return 'clear';
  return kind === 'stationChanged' || kind === 'inFlight' || kind === 'requestIdReused' || kind === 'invalidArgs'
    ? 'clear'
    : 'keep';
}
