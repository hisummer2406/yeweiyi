import type { Alarm, OilInOutRow, RecordListResponse, ShiftRecordRow, StockRecordRow } from '../types/api';
import type { Msg } from './i18nCore';

// The five record tables of 我的记录 (miniapp#17). Columns run left to right in the order the device's
// own display uses, so a customer can read the two side by side. Pure (no `wx`), checked in Node by
// `npm run check`; titles are i18n keys (locales/*.ts, group `table`), units are symbols shown as is.
export type RecordType = 'in' | 'out' | 'alarm' | 'stock' | 'shift';

export const RECORD_TYPES: readonly RecordType[] = ['in', 'out', 'alarm', 'stock', 'shift'];

export function isRecordType(v: unknown): v is RecordType {
  return typeof v === 'string' && (RECORD_TYPES as readonly string[]).includes(v);
}

export function directionOf(type: RecordType): 'IN' | 'OUT' | undefined {
  return type === 'in' ? 'IN' : type === 'out' ? 'OUT' : undefined;
}

export interface Column {
  titleKey: string;
  unit: string;
  align: 'left' | 'right';
  // Wider cell (times, alarm text).
  wide?: boolean;
}

const col = (titleKey: string, unit = '', align: 'left' | 'right' = unit ? 'right' : 'left', wide = false): Column => ({
  titleKey,
  unit,
  align,
  wide,
});

const TANK = col('table.tank');
const MEDIUM = col('table.medium');
const BEFORE_AFTER = [
  col('table.beginLevel', 'mm'),
  col('table.beginVolume', 'L'),
  col('table.endLevel', 'mm'),
  col('table.endVolume', 'L'),
];

// The first column (tank) is the fixed one; the page draws it apart from the scrolling ones.
export const COLUMNS: Record<RecordType, Column[]> = {
  in: [TANK, MEDIUM, col('table.inTime', '', 'left', true), ...BEFORE_AFTER, col('table.inQty', 'L'), col('table.inQty', 'kg')],
  out: [TANK, MEDIUM, col('table.outTime', '', 'left', true), ...BEFORE_AFTER, col('table.outQty', 'L'), col('table.outQty', 'kg')],
  alarm: [TANK, MEDIUM, col('table.alarmInfo', '', 'left', true), col('table.alarmTime', '', 'left', true)],
  stock: [
    TANK,
    MEDIUM,
    col('table.stockTime', '', 'left', true),
    col('table.level', 'mm'),
    col('table.volume', 'L'),
    col('table.water', 'mm'),
    col('table.temp', '℃'),
    col('table.mass', 'kg'),
  ],
  shift: [
    TANK,
    MEDIUM,
    col('table.shiftTime', '', 'left', true),
    col('table.takeoverTime', '', 'left', true),
    ...BEFORE_AFTER,
    col('table.outQty', 'L'),
    col('table.outQty', 'kg'),
  ],
};

// A value the cloud did not give is a dash — never 0, never a sample, never derived.
export const DASH = '—';

// Same rounding as utils/format.ts round2 (at most 2 decimals); repeated here because a module checked in
// Node cannot import another at runtime (the mini-program needs extensionless paths, Node does not resolve them).
export function num(v: unknown): string {
  return typeof v === 'number' && Number.isFinite(v) ? String(Math.round((v + Number.EPSILON) * 100) / 100) : DASH;
}

function text(v: unknown): string {
  return typeof v === 'string' && v.trim() ? v : DASH;
}

// The device's built-in oil list (cloud/fixtures/protocol/v1/oil_type_index.json): 1–31 have a fixed
// meaning and are shown in the app's language (locales `oil.N`, translated from the Chinese names: the
// device's own English list is misaligned, zhongchengyeweiyi#52, and abbreviated). 0 is "not set"; 32–39 are
// names the station typed on the device, so only the device's text (`oilType`) can show them.
// Firmware before test build E used 18–25 for those custom names (#11) and nothing on the wire tells the
// firmware apart: in 18–25 the built-in name is used only when the device text is that index's name on the
// newer firmware (Chinese or the device's English abbreviation, from oil_type_index.json — keep in sync);
// anything else is treated as a custom name (shown as is, or a dash when empty).
export const BUILTIN_OIL_MAX = 31;
const LEGACY_CUSTOM_DEVICE_NAMES: Record<number, readonly string[]> = {
  18: ['丙二醇', 'PG'],
  19: ['乙醇', 'Ethanol'],
  20: ['大豆油', 'Soy Oil'],
  21: ['棕榈油', 'Palm Oil'],
  22: ['轮胎油', 'Tire Oil'],
  23: ['石蜡', 'Paraffin'],
  24: ['二氯甲烷', 'DCM'],
  25: ['二厂盐酸', 'P2 HCl'],
};

export function oilTypeKey(index: unknown): string | null {
  return typeof index === 'number' && Number.isInteger(index) && index >= 1 && index <= BUILTIN_OIL_MAX ? `oil.${index}` : null;
}

// 介质 cell: by oilTypeIndex first, else the device's text as is, else a dash. `tr` renders an i18n key.
export function mediumText(index: unknown, raw: unknown, tr: (key: string) => string): string {
  const key = oilTypeKey(index);
  const deviceNames = typeof index === 'number' ? LEGACY_CUSTOM_DEVICE_NAMES[index] : undefined;
  const legacyCustom = deviceNames !== undefined && !(typeof raw === 'string' && deviceNames.includes(raw.trim()));
  return key && !legacyCustom ? tr(key) : text(raw);
}

export interface CellFormat {
  time: (iso: string) => string;
  tr: (key: string) => string;
}

// A time the cloud did not give (an open shift's end) is a dash, not "now" or the epoch.
function timeOr(v: unknown, f: CellFormat): string {
  return typeof v === 'string' && v ? f.time(v) : DASH;
}

// Cells after the tank column of an in/out row. The quantity is the cloud's delivered volume (what
// actually arrived or left, net of sales during the delivery), not end minus begin. The kg quantity is the
// device's own delivered mass (cloud#85); older records have none and show a dash — a weight difference
// is not the delivered mass and is never put in its place.
export function stockCells(r: OilInOutRow, f: CellFormat): string[] {
  return [
    mediumText(r.oilTypeIndex, r.oilType, f.tr),
    f.time(r.time),
    num(r.beginLevelMm),
    num(r.beginVolumeL),
    num(r.endLevelMm),
    num(r.endVolumeL),
    num(r.deliveredVolumeL),
    num(r.deliveredWeightKg),
  ];
}

// Cells after the tank column of an alarm row. The medium is the oil at the time of the alarm (cloud#85);
// device-level and older alarms have none: a dash, never the tank's current oil type.
// `info` is the rendered type + text.
export function alarmCells(a: Alarm, info: string, f: CellFormat): string[] {
  return [mediumText(a.oilTypeIndex, a.oilType, f.tr), info, f.time(a.time)];
}

// 交班: 交班时间 (endTime, the handover) comes before 接班时间 (beginTime, the takeover), as on the display.
// An open shift has no handover time and no end/out figures yet: dashes.
export function shiftCells(r: ShiftRecordRow, f: CellFormat): string[] {
  return [
    mediumText(r.oilTypeIndex, r.oilType, f.tr),
    timeOr(r.endTime, f),
    timeOr(r.beginTime, f),
    num(r.beginLevelMm),
    num(r.beginVolumeL),
    num(r.endLevelMm),
    num(r.endVolumeL),
    num(r.outVolumeL),
    num(r.outWeightKg),
  ];
}

// 库存. Level and water are millimetres (the design draft's "毫升" is wrong, cloud#85).
export function inventoryCells(r: StockRecordRow, f: CellFormat): string[] {
  return [
    mediumText(r.oilTypeIndex, r.oilType, f.tr),
    timeOr(r.time, f),
    num(r.levelMm),
    num(r.volumeL),
    num(r.waterLevelMm),
    num(r.tempC),
    num(r.weightKg),
  ];
}

// Row identities for wx:key and for dropping a row that shows up twice across a page boundary. One
// alarmId appears twice (ACTIVE and CLEARED), and the cloud blanks alarmId / eventId for records it
// minted itself, so neither id alone is a key.
export function alarmRowKey(a: Alarm): string {
  return `${a.alarmId}:${a.tank}:${a.type}:${a.status}:${a.time}`;
}

export function stockRowKey(r: OilInOutRow): string {
  return `${r.eventId}|${r.time}|${r.tank}|${r.direction}`;
}

// One row per (shift, tank) / (record, tank). An open shift keeps its key when it closes, so a refreshed
// row replaces the open one instead of showing twice.
export function shiftRowKey(r: ShiftRecordRow): string {
  return `${r.shiftId}|${r.tank}`;
}

export function inventoryRowKey(r: StockRecordRow): string {
  return `${r.recordId}|${r.tank}`;
}

// The record a row belongs to: the rows of one shift (or one stock snapshot) are drawn as one group.
export function groupOf(type: RecordType, row: { shiftId?: string; recordId?: string }): string {
  if (type === 'shift') return row.shiftId || '';
  if (type === 'stock') return row.recordId || '';
  return '';
}

// ---- Completeness of a 交班 / 库存 list (cloud#85 §4) ----

export function isRecordListResponse(v: unknown): v is RecordListResponse<unknown> {
  return typeof v === 'object' && v !== null && Array.isArray((v as { rows?: unknown }).rows);
}

export interface SyncSummary {
  // The device has never reported this kind of record: say so instead of a table or "no records".
  unsupported: boolean;
  // Only a SYNCED answer may turn an empty list into "no records". An unknown status (a newer cloud) is
  // treated as "cannot know".
  canSayEmpty: boolean;
  // Why the list may not be whole (i18n keys, `records.*`), shown above the table.
  notes: Msg[];
}

export function syncSummary(res: RecordListResponse<unknown>): SyncSummary {
  const status = res.syncStatus;
  const notes: Msg[] = [];
  if (status === 'SYNCING') notes.push({ key: 'records.syncing' });
  else if (status !== 'SYNCED' && status !== 'UNSUPPORTED') notes.push({ key: 'records.syncUnknown' });
  else if (res.partial) notes.push({ key: 'records.syncing' });
  const unparsable = typeof res.unparsable === 'number' && res.unparsable > 0 ? res.unparsable : 0;
  if (unparsable) notes.push({ key: 'records.unparsable', params: { n: unparsable } });
  return { unsupported: status === 'UNSUPPORTED', canSayEmpty: status === 'SYNCED', notes };
}
