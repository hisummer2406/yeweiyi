// 储罐设置的手机草稿（miniapp#18）。Pure (no `wx`), checked in Node by `npm run check`.
//
// The form is saved on this phone only. Nothing here is sent to the cloud or the controller, and a draft
// is never the device's configuration: reading and writing the controller's settings is cloud#70/#71.
// A draft belongs to one account, one device and one protocol tank number, so another account, another
// tank, or the station's replacement device never opens it.

export type FieldKind = 'positive' | 'nonNegative' | 'signed';

export interface DraftField {
  key: string;
  // Unit as shown next to the input; 'mm' / 'L' are rendered through the form texts.
  unit: 'kg/m³' | 'mm' | 'L' | '℃';
  kind: FieldKind;
}

// Top to bottom as in the design draft (物料颜色 and 介质类型 come first and are not numbers).
export const DRAFT_FIELDS: readonly DraftField[] = [
  { key: 'density', unit: 'kg/m³', kind: 'positive' },
  { key: 'height', unit: 'mm', kind: 'positive' },
  { key: 'capacity', unit: 'L', kind: 'positive' },
  { key: 'highHigh', unit: 'mm', kind: 'nonNegative' },
  { key: 'high', unit: 'mm', kind: 'nonNegative' },
  { key: 'lowLow', unit: 'mm', kind: 'nonNegative' },
  { key: 'low', unit: 'mm', kind: 'nonNegative' },
  { key: 'waterHigh', unit: 'mm', kind: 'nonNegative' },
  { key: 'waterLow', unit: 'mm', kind: 'nonNegative' },
  { key: 'tempHigh', unit: '℃', kind: 'signed' },
  { key: 'tempLow', unit: '℃', kind: 'signed' },
];

export interface TankDraft {
  // Index into the page's 12-colour palette; -1 = not set.
  color: number;
  medium: string;
  // Field key → the text as typed. '' = not set, which is different from "0".
  values: Record<string, string>;
  // Phone clock at the save (ms); 0 = never saved.
  savedAt: number;
}

// "Restore defaults": every field not set. There is no known device default to restore, and writing 0
// would claim a threshold the device may not have.
export function emptyDraft(): TankDraft {
  const values: Record<string, string> = {};
  for (const f of DRAFT_FIELDS) values[f.key] = '';
  return { color: -1, medium: '', values, savedAt: 0 };
}

// Storage key: account (userKeyFromToken, "u12") + device name + tank number. The device name is an
// opaque string, encoded rather than parsed (as utils/stationDevice.ts tankKey).
export function draftKey(userKey: string, device: string, tank: number): string {
  return `tankcfg-draft:${userKey}:${encodeURIComponent(device)}/${tank}`;
}

const NUMBER = /^-?\d+(\.\d+)?$/;

export type DraftProblem = { field: string; reason: 'number' | 'positive' | 'nonNegative' };

// Checks what was typed: a plain decimal number (or nothing), positive where a zero makes no sense
// (density, height, capacity), not negative for levels, any sign for temperatures. It is only a form
// check — whether the controller would accept the values is not known here.
export function validateDraft(values: Record<string, string>): DraftProblem | null {
  for (const f of DRAFT_FIELDS) {
    const raw = (values[f.key] ?? '').trim();
    if (raw === '') continue;
    if (!NUMBER.test(raw)) return { field: f.key, reason: 'number' };
    const n = Number(raw);
    if (f.kind === 'positive' && !(n > 0)) return { field: f.key, reason: 'positive' };
    if (f.kind === 'nonNegative' && n < 0) return { field: f.key, reason: 'nonNegative' };
  }
  return null;
}

// What a saved draft is read back as. Anything unexpected in storage (an older shape, a broken value)
// reads as fields not set, never as numbers made up to fill the form.
export function readDraft(stored: unknown): TankDraft | null {
  if (!stored || typeof stored !== 'object') return null;
  const s = stored as Partial<TankDraft>;
  const base = emptyDraft();
  const values = s.values && typeof s.values === 'object' ? s.values : {};
  for (const f of DRAFT_FIELDS) {
    const v = (values as Record<string, unknown>)[f.key];
    base.values[f.key] = typeof v === 'string' ? v : '';
  }
  return {
    color: typeof s.color === 'number' && Number.isInteger(s.color) && s.color >= 0 ? s.color : -1,
    medium: typeof s.medium === 'string' ? s.medium : '',
    values: base.values,
    savedAt: typeof s.savedAt === 'number' ? s.savedAt : 0,
  };
}

// Same content (ignoring savedAt): the form has no unsaved change.
export function sameDraft(a: TankDraft, b: TankDraft): boolean {
  if (a.color !== b.color || a.medium !== b.medium) return false;
  return DRAFT_FIELDS.every((f) => (a.values[f.key] ?? '') === (b.values[f.key] ?? ''));
}
