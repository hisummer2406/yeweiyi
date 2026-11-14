// 参数修正 / 储罐设置 的编辑表单与自动草稿（Issue #43）。Pure (no `wx`), checked in Node by `npm run check`.
//
// The form starts from the device's last report (the tank-settings mirror). Only what the user changed is an
// edit; an edit is stored with the report value it was made against (`base`), so a later report can be told
// apart from the user's input: untouched fields follow the new report, edited ones stay and are flagged when
// their report moved, and an edit the device now reports anyway disappears. A draft belongs to one kind, one
// account, one full device name and one protocol tank number; anything else never opens it.
import type { ParamKind } from './remoteConfig';

export type { ParamKind };

export function paramDraftKey(kind: ParamKind, userKey: string, device: string, tank: number): string {
  return `param-draft:${kind}:${userKey || 'anon'}:${encodeURIComponent(device)}#${tank}`;
}

export type ParamDraft = {
  v: 1;
  kind: ParamKind;
  user: string;
  device: string;
  tank: number;
  // form key → text as typed (only changed fields)
  edits: Record<string, string>;
  // form key → the report value ('' = not reported) the edit was made against
  base: Record<string, string>;
  savedAt: number;
};

export type DraftTarget = { kind: ParamKind; user: string; device: string; tank: number };

function isStringRecord(v: unknown): v is Record<string, string> {
  return !!v && typeof v === 'object' && !Array.isArray(v) && Object.values(v as object).every((x) => typeof x === 'string');
}

// A stored draft, only when it is for exactly this target and well formed.
export function readParamDraft(raw: unknown, target: DraftTarget): ParamDraft | null {
  if (!raw || typeof raw !== 'object') return null;
  const d = raw as Partial<ParamDraft>;
  if (d.v !== 1 || d.kind !== target.kind || d.user !== target.user || d.device !== target.device || d.tank !== target.tank) return null;
  if (!isStringRecord(d.edits) || !isStringRecord(d.base) || typeof d.savedAt !== 'number') return null;
  return d as ParamDraft;
}

const NUMBER = /^-?\d+(\.\d+)?$/;

// Same value as far as the device is concerned: "2" and "2.0" are the same number.
export function sameValue(a: string, b: string): boolean {
  const x = a.trim();
  const y = b.trim();
  if (NUMBER.test(x) && NUMBER.test(y)) return Number(x) === Number(y);
  return x === y;
}

// Whether the text typed into a field is an edit of that field. Empty is "leave as is" (buildArgs never
// sends it), so it is not an edit; neither is the report value itself.
export function isEdit(value: string, report: string): boolean {
  return value.trim() !== '' && !sameValue(value, report);
}

export type Merged = {
  values: Record<string, string>;
  edits: Record<string, string>;
  base: Record<string, string>;
  // edited fields whose report changed since the edit
  moved: string[];
};

// The form after a (new) report: report values, with the kept edits on top.
export function mergeReport(
  fields: readonly { form: string }[],
  report: Record<string, string>,
  edits: Record<string, string>,
  base: Record<string, string>,
): Merged {
  const out: Merged = { values: {}, edits: {}, base: {}, moved: [] };
  for (const f of fields) {
    const now = report[f.form] ?? '';
    const typed = edits[f.form];
    if (typed !== undefined && isEdit(typed, now)) {
      out.values[f.form] = typed;
      out.edits[f.form] = typed;
      const was = base[f.form] ?? '';
      out.base[f.form] = was;
      if (!sameValue(was, now)) out.moved.push(f.form);
    } else {
      out.values[f.form] = now;
    }
  }
  return out;
}

// After the user typed into one field.
export function applyInput(
  state: Merged,
  field: string,
  value: string,
  report: Record<string, string>,
): Merged {
  const now = report[field] ?? '';
  const next: Merged = {
    values: { ...state.values, [field]: value },
    edits: { ...state.edits },
    base: { ...state.base },
    moved: state.moved.filter((k) => k !== field),
  };
  if (isEdit(value, now)) {
    next.edits[field] = value;
    // Typing again is the review: the edit is now made against the current report.
    next.base[field] = now;
  } else {
    delete next.edits[field];
    delete next.base[field];
  }
  return next;
}

export function hasEdits(state: { edits: Record<string, string> }): boolean {
  return Object.keys(state.edits).length > 0;
}

export function toDraft(target: DraftTarget, state: Merged, now: number): ParamDraft {
  return { v: 1, ...target, edits: { ...state.edits }, base: { ...state.base }, savedAt: now };
}
