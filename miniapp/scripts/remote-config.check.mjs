// Run with: npm run check   (node --experimental-strip-types; pure logic only, no wx)
import assert from 'node:assert/strict';
import {
  CORRECTION_SEND_FIELDS,
  TANK_SEND_FIELDS,
  buildArgs,
  buildMediumArgs,
  ignoredFields,
  buildBackfill,
  classifySubmit,
  commandError,
  commandPhase,
  echoState,
  localRfc3339,
  mirrorToForm,
  newRequestId,
  offsetAtLocal,
  backfillFingerprint,
  pendingAfter,
  pendingStorageKey,
  readPending,
  withPending,
  withoutPending,
  toggleSign,
  splitSigned,
  joinSigned,
} from '../miniprogram/utils/remoteConfig.ts';

let n = 0;
const test = (name, fn) => {
  fn();
  n++;
  console.log('ok -', name);
};

const mirror = {
  tank: 1,
  reportedAt: '2026-11-08T14:00:07Z',
  alarmHighHighLevelMm: 2300,
  alarmHighLevelMm: 600,
  alarmLowLevelMm: 300,
  alarmLowLowLevelMm: 0,
  capacityL: 30000,
  density: 0.56,
  levelCorrectionMm: 0,
};

test('only filled fields that differ from the device are sent; empty is "leave as is"', () => {
  const r = buildArgs(TANK_SEND_FIELDS, { high: '610', highHigh: '2300', low: '', density: '0.56' }, mirror);
  assert.deepEqual(r, { args: { alarmHighLevelMm: 610 }, problems: [] });
});

test('low water is never sent; density (2026-11-14), height and low temperature (#65) are', () => {
  const keys = TANK_SEND_FIELDS.map((f) => f.form);
  assert.ok(!keys.includes('waterLow'));
  for (const k of ['density', 'height', 'tempLow']) assert.ok(keys.includes(k), k);
});

test('number, integer, range problems', () => {
  assert.deepEqual(buildArgs(TANK_SEND_FIELDS, { high: 'abc' }, mirror).problems, [{ form: 'high', reason: 'number' }]);
  assert.deepEqual(buildArgs(TANK_SEND_FIELDS, { high: '610.5' }, mirror).problems, [{ form: 'high', reason: 'integer' }]);
  assert.deepEqual(buildArgs(TANK_SEND_FIELDS, { tempHigh: '120' }, mirror).problems, [{ form: 'tempHigh', reason: 'range' }]);
  assert.deepEqual(buildArgs(CORRECTION_SEND_FIELDS, { levelOffset: '-1.5' }, mirror).args, { levelCorrectionMm: -1.5 });
  // #28: corrections have no range limit, but must stay real numbers
  assert.deepEqual(buildArgs(CORRECTION_SEND_FIELDS, { levelOffset: '1000', waterOffset: '-1000.5', tempOffset: '-100.5' }, mirror),
    { args: { levelCorrectionMm: 1000, waterCorrectionMm: -1000.5, tempCorrectionC: -100.5 }, problems: [] });
  assert.deepEqual(buildArgs(CORRECTION_SEND_FIELDS, { levelOffset: '9'.repeat(400) }, mirror).problems, [{ form: 'levelOffset', reason: 'number' }]);
  for (const bad of ['-', '--1', '1.', '.5', '-.']) {
    assert.deepEqual(buildArgs(CORRECTION_SEND_FIELDS, { tempOffset: bad }, mirror).problems, [{ form: 'tempOffset', reason: 'number' }], bad);
  }
});

test('digit keyboard: the sign lives outside the input and survives typing (#56)', () => {
  assert.deepEqual(splitSigned('-1.5'), { neg: true, mag: '1.5' });
  assert.deepEqual(splitSigned('500'), { neg: false, mag: '500' });
  assert.deepEqual(splitSigned('-'), { neg: true, mag: '' });
  assert.deepEqual(splitSigned(''), { neg: false, mag: '' });
  // ± first, then type: 500 → -500; keep typing, delete, clear
  let v = toggleSign('');
  assert.equal(v, '-');
  for (const typed of ['5', '50', '500', '50', '5', '']) v = joinSigned(v, typed);
  assert.equal(v, '-', 'clearing keeps the bare sign, never 0');
  v = joinSigned(v, '1.');
  assert.equal(v, '-1.', 'unfinished decimal stays as typed');
  v = joinSigned(v, '1.5');
  assert.equal(v, '-1.5');
  v = toggleSign(v);
  assert.equal(v, '1.5');
  assert.equal(joinSigned(v, '1.50'), '1.50');
  assert.equal(joinSigned('0', '0'), '0');
  assert.equal(joinSigned('-0', '0'), '-0');
  // a keyboard that can type '-' wins over the stored sign
  assert.equal(joinSigned('', '-3'), '-3');
  assert.equal(joinSigned('-1', '-3'), '-3');
  // round trip: what the input shows + the sign = the form text
  for (const text of ['-12.25', '100', '0', '-0.5', '-']) {
    const { neg, mag } = splitSigned(text);
    assert.equal(joinSigned(text, mag), text);
    assert.equal((neg ? '-' : '') + mag, text);
  }
  // incomplete values are still refused at send time
  const f = CORRECTION_SEND_FIELDS[0];
  assert.ok(buildArgs([f], { [f.form]: '-' }, {}).problems.length === 1);
  assert.ok(buildArgs([f], { [f.form]: '-1.' }, {}).problems.length === 1);
});

test('toggleSign flips the text and never steps (#30)', () => {
  assert.equal(toggleSign('1.5'), '-1.5');
  assert.equal(toggleSign('-1.5'), '1.5');
  assert.equal(toggleSign(''), '-');
  assert.equal(toggleSign('-'), '');
  assert.equal(toggleSign(' 0 '), '-0');
});

test('level order on report + changes, 0 = off', () => {
  assert.deepEqual(buildArgs(TANK_SEND_FIELDS, { high: '250' }, mirror).problems, [{ form: 'high', reason: 'order' }]);
  assert.deepEqual(buildArgs(TANK_SEND_FIELDS, { low: '0', high: '100' }, mirror).problems, []);
});

test('import fills reported values, leaves unreported empty', () => {
  const f = mirrorToForm(TANK_SEND_FIELDS, mirror);
  assert.equal(f.high, '600');
  assert.equal(f.tempHigh, '');
  assert.equal(f.lowLow, '0');
});

test('request ids match the cloud pattern', () => {
  assert.match(newRequestId(), /^[A-Za-z0-9_-]{1,64}$/);
  assert.notEqual(newRequestId(), newRequestId());
});

test('phase: ACK is not success', () => {
  assert.equal(commandPhase('sent'), 'accepted');
  assert.equal(commandPhase('received'), 'received');
  assert.equal(commandPhase('x'), 'unknown');
});

test('echo: waiting until a fresh equal report', () => {
  const issued = '2026-11-08T14:00:05Z'; // used as finishedAt
  assert.equal(echoState({ ...mirror, reportedAt: '2026-11-08T13:00:00Z' }, { tank: 1, alarmHighLevelMm: 600 }, issued), 'waiting');
  assert.equal(echoState(mirror, { tank: 1, alarmHighLevelMm: 600 }, issued), 'matched');
  assert.equal(echoState(mirror, { tank: 1, alarmHighLevelMm: 610 }, issued), 'mismatch');
  assert.equal(echoState(null, { alarmHighLevelMm: 610 }, issued), 'waiting');
});

test('echo: judged against finishedAt, not issuedAt (#18 F3)', () => {
  const m = (reportedAt, v = 600) => ({ ...mirror, reportedAt, alarmHighLevelMm: v });
  const a = { tank: 1, alarmHighLevelMm: 600 };
  // issued 00:00:00, report 00:00:05, finished 00:00:10: the report predates completion
  assert.equal(echoState(m('2026-11-08T00:00:05Z'), a, '2026-11-08T00:00:10Z'), 'waiting');
  assert.equal(echoState(m('2026-11-08T00:00:10Z'), a, '2026-11-08T00:00:10Z'), 'waiting');
  assert.equal(echoState(m('2026-11-08T00:00:11Z'), a, '2026-11-08T00:00:10Z'), 'matched');
  assert.equal(echoState(m('2026-11-08T00:00:11Z', 610), a, '2026-11-08T00:00:10Z'), 'mismatch');
  // missing / invalid finishedAt: cannot confirm
  assert.equal(echoState(m('2026-11-08T00:00:11Z'), a, undefined), 'waiting');
  assert.equal(echoState(m('2026-11-08T00:00:11Z'), a, 'garbage'), 'waiting');
  assert.equal(echoState(m('garbage'), a, '2026-11-08T00:00:10Z'), 'waiting');
});

test('submit errors by code; no answer is unconfirmed', () => {
  assert.equal(classifySubmit(409, { code: 'config_commands_disabled', scope: 'device' }), 'deviceDisabled');
  assert.equal(classifySubmit(409, { code: 'config_commands_disabled', scope: 'deployment' }), 'deploymentDisabled');
  assert.equal(classifySubmit(409, { code: 'device_station_changed' }), 'stationChanged');
  assert.equal(classifySubmit(0, undefined), 'unconfirmed');
  assert.equal(classifySubmit(503, { code: 'command_delivery_unconfirmed' }), 'unconfirmed');
  assert.equal(classifySubmit(500, {}), 'other');
});

test('backfill time carries the phone offset', () => {
  assert.equal(localRfc3339('2026-10-14', '08:30', -480), '2026-10-14T08:30:00+08:00');
  assert.equal(localRfc3339('2026-10-14', '08:30', 300), '2026-10-14T08:30:00-05:00');
  assert.equal(localRfc3339('2026-10-14', '', -480), null);
});

test('backfill validation', () => {
  const now = Date.parse('2026-11-08T12:00:00+08:00');
  const ok = { date: '2026-11-07', time: '08:30', beforeLevel: '1333.3', volume: '540' };
  const r = buildBackfill('IN', 1, ok, ' 断电漏记 ', 'mp-1', now, -480);
  assert.deepEqual(r.body, {
    direction: 'IN', tank: 1, time: '2026-11-07T08:30:00+08:00', beforeLevelMm: 1333.3, volumeL: 540, reason: '断电漏记', requestId: 'mp-1',
  });
  assert.equal(buildBackfill('IN', undefined, ok, 'r', 'x', now, -480).problem, 'tank');
  assert.equal(buildBackfill('IN', 1, { ...ok, date: '2026-11-09' }, 'r', 'x', now, -480).problem, 'future');
  assert.equal(buildBackfill('IN', 1, { ...ok, volume: '0' }, 'r', 'x', now, -480).problem, 'volume');
  assert.equal(buildBackfill('IN', 1, { ...ok, beforeLevel: '-1' }, 'r', 'x', now, -480).problem, 'level');
  assert.equal(buildBackfill('IN', 1, ok, '  ', 'x', now, -480).problem, 'reason');
});

test('the offset is the one at the chosen local time, not today (daylight saving)', () => {
  const saved = process.env.TZ;
  process.env.TZ = 'America/New_York';
  try {
    assert.equal(offsetAtLocal('2026-07-01', '08:30'), 240); // EDT, UTC-4
    assert.equal(offsetAtLocal('2026-11-15', '08:30'), 300); // EST, UTC-5
    assert.equal(localRfc3339('2026-07-01', '08:30', offsetAtLocal('2026-07-01', '08:30')), '2026-07-01T08:30:00-04:00');
    assert.equal(offsetAtLocal('2026-07-01', ''), null);
  } finally {
    if (saved === undefined) delete process.env.TZ;
    else process.env.TZ = saved;
  }
});

test('the same typed entry has the same fingerprint; any changed field changes it', () => {
  const e = { date: '2026-11-07', time: '08:30', beforeLevel: '1333.3', volume: '540' };
  assert.equal(backfillFingerprint(0, e, 'r'), backfillFingerprint(0, { ...e, volume: ' 540 ' }, ' r '));
  assert.notEqual(backfillFingerprint(0, e, 'r'), backfillFingerprint(1, e, 'r'));
  assert.notEqual(backfillFingerprint(0, e, 'r'), backfillFingerprint(0, { ...e, time: '08:31' }, 'r'));
});

test('pending submission: per account key, per device+tank, malformed dropped (#18 F2)', () => {
  assert.equal(pendingStorageKey('u12'), 'cfg-pending:u12');
  assert.notEqual(pendingStorageKey('u12'), pendingStorageKey('u34'));
  const p = { requestId: 'mp-1', device: 'D', stationId: 7, tank: 1, args: { alarmHighLevelMm: 700 }, reason: 'r', lines: [], savedAt: 1 };
  const st = withPending(undefined, p);
  assert.deepEqual(readPending(st, 'D', 1), p);
  assert.equal(readPending(st, 'D', 2), undefined);
  assert.equal(readPending(st, 'E', 1), undefined);
  assert.equal(readPending(withoutPending(st, 'D', 1), 'D', 1), undefined);
  // #49: a tank-table snapshot carries its method and a string shape; an unknown method is dropped.
  const table = { ...p, method: 'config.setTankTable', args: { shape: 'vertical', heightMm: 2500, diameterMm: 2000 } };
  assert.deepEqual(readPending(withPending({}, table), 'D', 1), table);
  assert.equal(readPending(withPending({}, { ...table, method: 'pump.start' }), 'D', 1), undefined);
  assert.deepEqual(readPending(withPending(st, { ...p, tank: 2 }), 'D', 1), p);
  assert.deepEqual(readPending(withPending(undefined, { ...p, stationId: null }), 'D', 1).stationId, null);
  for (const bad of [{ ...p, requestId: '' }, { ...p, args: { a: 'x' } }, { ...p, stationId: '7' }, { ...p, lines: null }]) {
    assert.equal(readPending({ 'D#1': bad }, 'D', 1), undefined);
  }
  assert.equal(readPending('', 'D', 1), undefined);
});

test('pending submission: kept while unknown; a retry is settled only by a verdict on its id', () => {
  assert.equal(pendingAfter('ok'), 'clear');
  assert.equal(pendingAfter('notSent'), 'clear');
  assert.equal(pendingAfter('unconfirmed'), 'keep');
  assert.equal(pendingAfter('unconfirmed', true), 'keep');
  for (const k of ['stationChanged', 'inFlight', 'invalidArgs', 'requestIdReused', 'deploymentDisabled', 'other']) {
    assert.equal(pendingAfter(k), 'clear', `first send ${k}`);
  }
  for (const k of ['stationChanged', 'inFlight', 'invalidArgs', 'requestIdReused']) assert.equal(pendingAfter(k, true), 'clear', k);
  for (const k of ['deploymentDisabled', 'deviceDisabled', 'other']) assert.equal(pendingAfter(k, true), 'keep', k);
});

test('commandError: only refusals/failures explain; the ACK "已受理" never does (#42)', () => {
  // legacy firmware: received ACK ("已受理") then cmdResult rejected — the cloud stores the device's reason
  assert.deepEqual(commandError('rejected', 'INVALID_PARAMS', '液位修正超出范围'), { text: '液位修正超出范围' });
  assert.deepEqual(commandError('rejected', 'INVALID_PARAMS', ''), { key: 'cmdLog.code.INVALID_PARAMS' });
  assert.deepEqual(commandError('failed', 'HARDWARE_FAULT', undefined), { key: 'cmdLog.code.HARDWARE_FAULT' });
  assert.deepEqual(commandError('failed', 'VENDOR_X', ''), { text: 'VENDOR_X' });
  assert.equal(commandError('failed', '', ''), null);
  // truly no result: still "结果无法确认", without the stale ACK reason
  assert.equal(commandError('expired', '', '已受理'), null);
  assert.equal(commandError('received', '', '已受理'), null);
  assert.equal(commandError('succeeded', '', '已受理'), null);
});

test('density is sendable in g/cm³, > 0 and ≤ 2; an untouched odd report is not sent', () => {
  assert.deepEqual(buildArgs(TANK_SEND_FIELDS, { density: '0.84' }, { density: 0.56 }).args, { density: 0.84 });
  for (const bad of ['0', '2.5', '-1']) {
    assert.deepEqual(buildArgs(TANK_SEND_FIELDS, { density: bad }, {}).problems, [{ form: 'density', reason: 'range' }]);
  }
  assert.deepEqual(buildArgs(TANK_SEND_FIELDS, { density: '840', high: '1800' }, { density: 840 }), { args: { alarmHighLevelMm: 1800 }, problems: [] });
});

test('medium and colour: only what was chosen and differs from the report', () => {
  assert.deepEqual(buildMediumArgs(5, undefined, { oilColor: null }), { mediaId: 5 });
  assert.deepEqual(buildMediumArgs(undefined, 0xff0000, { oilColor: 0x0000ff }), { oilColor: 0xff0000 });
  assert.deepEqual(buildMediumArgs(undefined, 0xff0000, { oilColor: 0xff0000 }), {});
  assert.deepEqual(buildMediumArgs(undefined, undefined, undefined), {});
});

test('ignoredFields reads the device detail', () => {
  assert.deepEqual(ignoredFields({ resultDetail: { tank: 1, updated: [], ignored: ['oilColor'] } }), ['oilColor']);
  assert.deepEqual(ignoredFields({ resultDetail: null }), []);
  assert.deepEqual(ignoredFields({}), []);
});

test('echo: the custom medium name the cloud adds is not part of the report, only its index is compared', () => {
  const args = { tank: 1, oilTypeIndex: 32, customOilName: '盐酸', customOilNameEn: 'HCl' };
  const fresh = { reportedAt: '2026-11-14T03:00:01Z', oilTypeIndex: 32, oilType: '盐酸' };
  assert.equal(echoState(fresh, args, '2026-11-14T03:00:00Z'), 'matched');
  assert.equal(echoState({ ...fresh, oilTypeIndex: 33 }, args, '2026-11-14T03:00:00Z'), 'mismatch');
});

test('outdated device software: refusals say so and ask to upgrade', () => {
  assert.equal(classifySubmit(409, { code: 'media_sync_unsupported' }), 'firmwareOutdated');
  assert.equal(classifySubmit(409, { code: 'color_sync_unsupported' }), 'firmwareOutdated');
  assert.equal(classifySubmit(409, { code: 'height_low_temp_unsupported' }), 'firmwareOutdated');
  assert.equal(pendingAfter('firmwareOutdated', true), 'clear');
  // the device's own wording for an unknown method is replaced by the upgrade hint
  assert.deepEqual(commandError('rejected', 'UNKNOWN_METHOD', 'unknown method config.setTankTable'), { key: 'cmdLog.code.UNKNOWN_METHOD' });
  assert.deepEqual(commandError('rejected', 'INVALID_PARAMS', '超出范围'), { text: '超出范围' });
});

console.log(`${n} checks passed`);
