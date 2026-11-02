// Run with: npm run check   (node --experimental-strip-types; pure logic only, no wx)
import assert from 'node:assert/strict';
import { commandOutcomeUnknown, telemetryFailure } from '../miniprogram/utils/failureState.ts';

let n = 0;
const test = (name, fn) => {
  fn();
  n++;
  console.log('ok -', name);
};

test('telemetry 404 is told apart by code (yeweiyi-cloud#79), not by status alone', () => {
  assert.deepEqual(telemetryFailure(404, { code: 'telemetry_not_reported' }), { kind: 'empty', key: 'telemetry.neverReported' });
  assert.deepEqual(telemetryFailure(404, { code: 'telemetry_expired', lastReportedAt: '2026-10-01T00:00:00Z' }), {
    kind: 'empty',
    key: 'telemetry.expired',
    lastReportedAt: '2026-10-01T00:00:00Z',
  });
  assert.deepEqual(telemetryFailure(404, { code: 'device_not_provisioned' }), { kind: 'empty', key: 'telemetry.notProvisioned' });
});

test('an older cloud (404 without a code) still means never reported', () => {
  assert.deepEqual(telemetryFailure(404, { error: 'not found' }), { kind: 'empty', key: 'telemetry.neverReported' });
  assert.deepEqual(telemetryFailure(404, undefined), { kind: 'empty', key: 'telemetry.neverReported' });
});

test('everything else is a failure, never an empty "no data" state', () => {
  assert.deepEqual(telemetryFailure(404, { code: 'something_new' }), { kind: 'error' });
  assert.deepEqual(telemetryFailure(503, { code: 'device_platform_unavailable' }), { kind: 'error' });
  assert.deepEqual(telemetryFailure(403, { code: 'device_not_authorized' }), { kind: 'error' });
  assert.deepEqual(telemetryFailure(0, undefined), { kind: 'error' });
});

test('a command whose hand-off is unconfirmed, or that got no answer, has an unknown outcome', () => {
  assert.equal(commandOutcomeUnknown(503, { code: 'command_delivery_unconfirmed' }), true);
  assert.equal(commandOutcomeUnknown(0, undefined), true);
  assert.equal(commandOutcomeUnknown(503, { code: 'audit_unavailable' }), false); // not sent: safe to retry
  assert.equal(commandOutcomeUnknown(503, { code: 'control_unavailable' }), false);
  assert.equal(commandOutcomeUnknown(409, { code: 'physical_commands_disabled' }), false);
});

console.log(`${n} checks passed`);
