// Run with: npm run check   (node --experimental-strip-types; pure logic only, no wx)
import assert from 'node:assert/strict';
import {
  CORRECTION_SEND_FIELDS,
  TANK_SEND_FIELDS,
  buildArgs,
  buildBackfill,
  classifySubmit,
  commandPhase,
  echoState,
  localRfc3339,
  mirrorToForm,
  newRequestId,
  offsetAtLocal,
  backfillFingerprint,
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
  const r = buildArgs(TANK_SEND_FIELDS, { high: '610', highHigh: '2300', low: '', density: '0.7' }, mirror);
  assert.deepEqual(r, { args: { alarmHighLevelMm: 610 }, problems: [] });
});

test('density, height, low water, low temperature are never sent', () => {
  const keys = TANK_SEND_FIELDS.map((f) => f.form);
  for (const k of ['density', 'height', 'waterLow', 'tempLow']) assert.ok(!keys.includes(k), k);
});

test('number, integer, range problems', () => {
  assert.deepEqual(buildArgs(TANK_SEND_FIELDS, { high: 'abc' }, mirror).problems, [{ form: 'high', reason: 'number' }]);
  assert.deepEqual(buildArgs(TANK_SEND_FIELDS, { high: '610.5' }, mirror).problems, [{ form: 'high', reason: 'integer' }]);
  assert.deepEqual(buildArgs(TANK_SEND_FIELDS, { tempHigh: '120' }, mirror).problems, [{ form: 'tempHigh', reason: 'range' }]);
  assert.deepEqual(buildArgs(CORRECTION_SEND_FIELDS, { levelOffset: '-1.5' }, mirror).args, { levelCorrectionMm: -1.5 });
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
  const issued = '2026-11-08T14:00:05Z';
  assert.equal(echoState({ ...mirror, reportedAt: '2026-11-08T13:00:00Z' }, { tank: 1, alarmHighLevelMm: 600 }, issued), 'waiting');
  assert.equal(echoState(mirror, { tank: 1, alarmHighLevelMm: 600 }, issued), 'matched');
  assert.equal(echoState(mirror, { tank: 1, alarmHighLevelMm: 610 }, issued), 'mismatch');
  assert.equal(echoState(null, { alarmHighLevelMm: 610 }, issued), 'waiting');
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

console.log(`${n} checks passed`);
