import type { Alarm, OilInOutRow } from '../types/api';

// The five record tables of 我的记录 (miniapp#17). Columns run left to right in the order the device's
// own display uses, so a customer can read the two side by side. Pure (no `wx`), checked in Node by
// `npm run check`; titles are i18n keys (locales/*.ts, group `table`), units are symbols shown as is.
export type RecordType = 'in' | 'out' | 'alarm' | 'stock' | 'shift';

export const RECORD_TYPES: readonly RecordType[] = ['in', 'out', 'alarm', 'stock', 'shift'];

export function isRecordType(v: unknown): v is RecordType {
  return typeof v === 'string' && (RECORD_TYPES as readonly string[]).includes(v);
}

// Stock (库存) and shift (交班) records have no cloud endpoint yet (yeweiyi-cloud#85, zhongchengyeweiyi#49):
// those pages say "not open yet" and send nothing, never an empty table.
export const SUPPORTED: Record<RecordType, boolean> = { in: true, out: true, alarm: true, stock: false, shift: false };

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

// Cells after the tank column of an in/out row. The quantity is the cloud's delivered volume (what
// actually arrived or left, net of sales during the delivery), not end minus begin. The kg quantity has no
// defined source yet (yeweiyi-cloud#85 item 4): a weight difference is not the delivered mass, so it
// stays a dash.
export function stockCells(r: OilInOutRow, time: (iso: string) => string): string[] {
  return [
    text(r.oilType),
    time(r.time),
    num(r.beginLevelMm),
    num(r.beginVolumeL),
    num(r.endLevelMm),
    num(r.endVolumeL),
    num(r.deliveredVolumeL),
    DASH,
  ];
}

// Cells after the tank column of an alarm row. Alarm records carry no medium (yeweiyi-cloud#85): a dash,
// not the tank's current oil type, which may not be what it held then. `info` is the rendered type + text.
export function alarmCells(a: Alarm, info: string, time: (iso: string) => string): string[] {
  return [DASH, info, time(a.time)];
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
