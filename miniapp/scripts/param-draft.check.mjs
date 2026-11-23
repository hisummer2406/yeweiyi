// Run with: npm run check   (node --experimental-strip-types; pure logic only, no wx)
import assert from 'node:assert/strict';
import {
  applyInput,
  hasEdits,
  isEdit,
  mergeReport,
  paramDraftKey,
  readParamDraft,
  sameValue,
  toDraft,
} from '../miniprogram/utils/paramDraft.ts';
import { buildArgs, editFields, sendableTankFields } from '../miniprogram/utils/remoteConfig.ts';

let n = 0;
const test = (name, fn) => {
  fn();
  n++;
  console.log('ok -', name);
};

const cal = editFields('calibration');
const report = { levelOffset: '2', waterOffset: '', tempOffset: '-0.5' };

test('calibration fields are the three corrections, signed, with units', () => {
  assert.deepEqual(cal.map((f) => [f.form, f.unit, f.signed]), [
    ['levelOffset', 'mm', true],
    ['waterOffset', 'mm', true],
    ['tempOffset', '℃', true],
  ]);
});

test('tank fields (density since 2026-11-14, height/low temp since #65); colour is picked, no low water', () => {
  const tank = editFields('tank');
  assert.deepEqual(tank.map((f) => f.form), ['capacity', 'height', 'highHigh', 'high', 'lowLow', 'low', 'waterHigh', 'tempHigh', 'tempLow', 'density']);
  assert.deepEqual(tank.filter((f) => f.signed).map((f) => f.form), ['tempHigh', 'tempLow']);
  assert.deepEqual(sendableTankFields(tank, false).map((f) => f.form), ['capacity', 'highHigh', 'high', 'lowLow', 'low', 'waterHigh', 'tempHigh', 'density']);
  assert.equal(sendableTankFields(tank, true).length, tank.length);
  assert.ok(!tank.some((f) => f.form === 'waterLow' || f.form === 'color'));
});

test('#65: height against thresholds, low temp against high temp', () => {
  const tank = editFields('tank');
  const mirror = { tankHeightMm: 2600, alarmHighHighLevelMm: 2400, alarmHighLevelMm: 2200, alarmLowLevelMm: 300, alarmLowLowLevelMm: 0,
    alarmHighWaterMm: 50, alarmHighTempC: 40, alarmLowTempC: 0, capacityL: 30000, density: 0.8 };
  const v = (o) => Object.fromEntries(Object.entries({ ...mirror, ...o }).map(([k, x]) => [tank.find((f) => f.cloud === k).form, String(x)]));
  let b = buildArgs(tank, v({ tankHeightMm: 2300 }), mirror);
  assert.deepEqual(b.problems, [{ form: 'highHigh', reason: 'overHeight' }]);
  b = buildArgs(tank, v({ tankHeightMm: 2300, alarmHighHighLevelMm: 2250 }), mirror);
  assert.deepEqual(b.problems, []);
  assert.deepEqual(b.args, { tankHeightMm: 2300, alarmHighHighLevelMm: 2250 });
  for (const bad of ['0', '-1', '2600.5']) {
    assert.equal(buildArgs(tank, { ...v({}), height: bad }, mirror).problems.length, 1, bad);
  }
  b = buildArgs(tank, v({ alarmLowTempC: -40 }), mirror);
  assert.deepEqual(b.args, { alarmLowTempC: -40 });
  assert.deepEqual(buildArgs(tank, v({ alarmLowTempC: 40 }), mirror).problems, [{ form: 'tempLow', reason: 'tempOrder' }]);
  assert.deepEqual(buildArgs(tank, v({ alarmLowTempC: 45, alarmHighTempC: 0 }), mirror).problems, []);
});

test('a new session is the report: no edits, missing stays empty (never 0)', () => {
  const m = mergeReport(cal, report, {}, {});
  assert.deepEqual(m.values, report);
  assert.equal(hasEdits(m), false);
  assert.equal(m.values.waterOffset, '');
});

test('typing a different value is an edit; typing the report value back or clearing is not', () => {
  let s = mergeReport(cal, report, {}, {});
  s = applyInput(s, 'levelOffset', '-1.5', report);
  assert.deepEqual(s.edits, { levelOffset: '-1.5' });
  assert.deepEqual(s.base, { levelOffset: '2' });
  s = applyInput(s, 'levelOffset', '2.0', report);
  assert.equal(hasEdits(s), false);
  s = applyInput(s, 'levelOffset', '', report);
  assert.equal(hasEdits(s), false);
  assert.equal(isEdit('0', ''), true, '0 on a not-reported field is a real value');
});

test('only the edited field is sent', () => {
  let s = mergeReport(cal, report, {}, {});
  s = applyInput(s, 'levelOffset', '-1.5', report);
  const mirror = { levelCorrectionMm: 2, waterCorrectionMm: null, tempCorrectionC: -0.5 };
  assert.deepEqual(buildArgs(cal, s.values, mirror), { args: { levelCorrectionMm: -1.5 }, problems: [] });
});

test('a new report: untouched fields follow it, edits stay, a moved edit is flagged, an edit now reported disappears', () => {
  let s = mergeReport(cal, report, {}, {});
  s = applyInput(s, 'levelOffset', '-1.5', report);
  s = applyInput(s, 'tempOffset', '1', report);
  const next = { levelOffset: '3', waterOffset: '4', tempOffset: '1' };
  const m = mergeReport(cal, next, s.edits, s.base);
  assert.deepEqual(m.values, { levelOffset: '-1.5', waterOffset: '4', tempOffset: '1' });
  assert.deepEqual(m.edits, { levelOffset: '-1.5' });
  assert.deepEqual(m.moved, ['levelOffset']);
  // typing again re-bases the edit on the current report (the user reviewed it)
  const r = applyInput(m, 'levelOffset', '-1', next);
  assert.deepEqual(r.moved, []);
  assert.deepEqual(r.base, { levelOffset: '3' });
});

test('drafts are isolated by kind, account, full device name and tank', () => {
  const target = { kind: 'calibration', user: 'u1', device: 'YW-001', tank: 3 };
  let s = mergeReport(cal, report, {}, {});
  s = applyInput(s, 'levelOffset', '-1.5', report);
  const d = toDraft(target, s, 1000);
  assert.deepEqual(readParamDraft(d, target)?.edits, { levelOffset: '-1.5' });
  assert.equal(readParamDraft(d, { ...target, user: 'u2' }), null);
  assert.equal(readParamDraft(d, { ...target, device: 'YW-0011' }), null);
  assert.equal(readParamDraft(d, { ...target, tank: 1 }), null);
  assert.equal(readParamDraft(d, { ...target, kind: 'tank' }), null);
  assert.equal(readParamDraft({ ...d, edits: { levelOffset: 3 } }, target), null);
  assert.equal(readParamDraft(null, target), null);
  assert.notEqual(paramDraftKey('calibration', 'u1', 'A/B', 1), paramDraftKey('calibration', 'u1', 'A', 1));
  assert.notEqual(paramDraftKey('calibration', 'u1', 'A', 1), paramDraftKey('tank', 'u1', 'A', 1));
});

test('sameValue compares numbers as numbers', () => {
  assert.ok(sameValue('2', '2.0'));
  assert.ok(sameValue(' -0.5', '-0.50'));
  assert.ok(!sameValue('-', ''));
  assert.ok(!sameValue('1', ''));
});

console.log(`${n} param-draft checks passed`);
