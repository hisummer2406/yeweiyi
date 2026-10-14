// Run with: npm run check   (node --experimental-strip-types; pure logic only, no wx)
import assert from 'node:assert/strict';
import {
  followStationDevice,
  stationIdFromKey,
  stationIdOf,
  stationParam,
  tankKey,
} from '../miniprogram/utils/stationDevice.ts';

const dev = (name, station_id) => ({
  device_name: name,
  is_online: false,
  last_online_at: null,
  last_offline_at: null,
  updated_at: '',
  ...(station_id === undefined ? {} : { station_id }),
});

let n = 0;
const test = (name, fn) => {
  fn();
  n++;
  console.log('ok -', name);
};

test('device still in its station: nothing changes', () => {
  assert.deepEqual(followStationDevice([dev('A', 1), dev('X', 2)], 'A', 1), { kind: 'same' });
});

test('A replaced by B in the same station: follow B, never keep A', () => {
  // back office: A unassigned (station_id null, still visible as ungrouped), B assigned to station 1
  const list = [dev('A', null), dev('B', 1)];
  assert.deepEqual(followStationDevice(list, 'A', 1), { kind: 'switch', device: 'B' });
});

test('replacement B the user is not authorized for is absent: no device, no fallback to A', () => {
  const list = [dev('A', null)];
  assert.deepEqual(followStationDevice(list, 'A', 1), { kind: 'none', reason: 'no-device' });
});

test('station emptied while waiting for the new device', () => {
  assert.deepEqual(followStationDevice([dev('Y', 2)], 'A', 1), { kind: 'none', reason: 'no-device' });
});

test('two devices in one station are never merged or picked', () => {
  const list = [dev('A', null), dev('B', 1), dev('C', 1)];
  assert.deepEqual(followStationDevice(list, 'A', 1), { kind: 'none', reason: 'ambiguous' });
});

test('a device opened outside any station only checks scope', () => {
  assert.deepEqual(followStationDevice([dev('A', null)], 'A', null), { kind: 'same' });
  assert.deepEqual(followStationDevice([dev('A')], 'A', undefined), { kind: 'same' });
  assert.deepEqual(followStationDevice([dev('Z', 1)], 'A', null), { kind: 'none', reason: 'device-gone' });
});

test('an old cloud without station_id never causes a switch', () => {
  assert.deepEqual(followStationDevice([dev('A'), dev('B')], 'A', undefined), { kind: 'same' });
});

test('device names are opaque: leading zeros and case are kept, no prefix is assumed', () => {
  const list = [dev('000123', null), dev('0007', 5)];
  assert.deepEqual(followStationDevice(list, '000123', 5), { kind: 'switch', device: '0007' });
  assert.equal(stationIdOf([dev('000123', 5)], '000123'), 5);
  assert.equal(stationIdOf([dev('000123', 5)], '123'), undefined);
});

test('tank identity is device name + tank number, so the same tank on another device differs', () => {
  assert.notEqual(tankKey('A', 1), tankKey('B', 1));
  assert.notEqual(tankKey('A', 1), tankKey('A', 2));
  assert.equal(tankKey('000123', 1), tankKey('000123', '1'));
  assert.notEqual(tankKey('000123', 1), tankKey('123', 1));
  // a name containing the separator cannot collide with another device/tank pair
  assert.notEqual(tankKey('A/1', 2), tankKey('A', '1/2'));
});

test('station key / query value round trip', () => {
  assert.equal(stationIdFromKey('12'), 12);
  assert.equal(stationIdFromKey('ungrouped'), null);
  assert.equal(stationIdFromKey('none'), null);
  assert.equal(stationIdFromKey(''), undefined);
  assert.equal(stationIdFromKey('abc'), undefined);
  assert.equal(stationParam(12), '12');
  assert.equal(stationParam(null), 'none');
  assert.equal(stationParam(undefined), '');
});

console.log(`${n} passed`);
