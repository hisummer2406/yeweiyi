// Run with: npm run check   (node --experimental-strip-types; pure logic only, no wx)
import assert from 'node:assert/strict';
import { recordsContext } from '../miniprogram/utils/stations.ts';

const st = (id, name) => ({ id, customerId: 1, name, address: '', createdAt: '' });
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

const stations = [st(1, '城东站'), st(2, '城西站'), st(3, '空站')];

test('remembered station: its one device, no picker', () => {
  const c = recordsContext([dev('A', 1), dev('B', 2)], stations, '2');
  assert.equal(c.kind, 'ok');
  assert.equal(c.stationName, '城西站');
  assert.equal(c.device.device_name, 'B');
  assert.equal(c.lost, false);
});

test('nothing remembered: the same default as the home page (first station with devices)', () => {
  const c = recordsContext([dev('B', 2)], stations, undefined);
  assert.equal(c.kind, 'ok');
  assert.equal(c.stationKey, '2');
});

test('remembered station gone: another is used and flagged lost', () => {
  const c = recordsContext([dev('A', 1)], [st(1, '城东站')], '9');
  assert.equal(c.kind, 'ok');
  assert.equal(c.lost, true);
});

test('empty station', () => {
  assert.deepEqual(recordsContext([dev('A', 1)], stations, '3'), { kind: 'empty-station', stationKey: '3', stationName: '空站' });
});

test('two devices in one station: never pick one', () => {
  assert.equal(recordsContext([dev('A', 1), dev('B', 1)], stations, '1').kind, 'ambiguous');
});

test('older cloud (no station_id) or no stations: no station', () => {
  assert.deepEqual(recordsContext([dev('A'), dev('B')], stations, '1'), { kind: 'no-station' });
  assert.deepEqual(recordsContext([dev('A', 1)], [], '1'), { kind: 'no-station' });
});

test('ungrouped is not a station, even with one device', () => {
  assert.deepEqual(recordsContext([dev('A', 1), dev('U', null)], stations, 'ungrouped'), { kind: 'no-station' });
});

test('pinned station is used; if no longer offered there is no fallback', () => {
  const c = recordsContext([dev('A', 1), dev('B', 2)], stations, '2', '1');
  assert.equal(c.kind, 'ok');
  assert.equal(c.device.device_name, 'A');
  assert.deepEqual(recordsContext([dev('A', 1)], stations, '1', '7'), { kind: 'no-station' });
});

console.log(`${n} records-context checks passed`);
