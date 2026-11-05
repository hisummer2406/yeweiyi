// Run with: npm run check   (node --experimental-strip-types; pure logic only, no wx)
import assert from 'node:assert/strict';
import {
  UNGROUPED_KEY,
  buildStationOptions,
  devicesOfStation,
  filterStations,
  pickStation,
} from '../miniprogram/utils/stations.ts';

const st = (id, name, address = '') => ({ id, customerId: 1, name, address, createdAt: '' });
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

test('degrades to no dropdown when the cloud returns no stations', () => {
  assert.deepEqual(buildStationOptions([], [dev('A', 1)]), []);
});

test('degrades when no device belongs to a known station (old cloud, all ungrouped)', () => {
  const stations = [st(1, '甲站')];
  assert.deepEqual(buildStationOptions(stations, [dev('A'), dev('B', null)]), []);
});

test('a station id the list does not know is ungrouped, not a crash', () => {
  const stations = [st(1, '甲站')];
  const devices = [dev('A', 1), dev('B', 99)];
  const opts = buildStationOptions(stations, devices);
  assert.deepEqual(opts.map((o) => o.key), ['1', UNGROUPED_KEY]);
  assert.deepEqual(devicesOfStation(devices, stations, UNGROUPED_KEY).map((d) => d.device_name), ['B']);
});

test('options carry device counts and the ungrouped option comes last, only when needed', () => {
  const stations = [st(1, '甲站', '某路'), st(2, '乙站')];
  const all = [dev('A', 1), dev('B', 1), dev('C', 2)];
  const opts = buildStationOptions(stations, all);
  assert.deepEqual(opts.map((o) => [o.key, o.deviceCount]), [['1', 2], ['2', 1]]);
  const withFree = buildStationOptions(stations, [...all, dev('D', null)], '未分组');
  assert.deepEqual(withFree.map((o) => [o.key, o.deviceCount]), [['1', 2], ['2', 1], [UNGROUPED_KEY, 1]]);
  assert.equal(withFree.at(-1).name, '未分组');
});

test('an empty station (customer admin) is kept as an option with count 0', () => {
  const stations = [st(1, '甲站'), st(2, '空站')];
  const opts = buildStationOptions(stations, [dev('A', 1)]);
  assert.deepEqual(opts.map((o) => [o.key, o.deviceCount]), [['1', 1], ['2', 0]]);
});

test('devicesOfStation filters by station key', () => {
  const stations = [st(1, '甲站'), st(2, '乙站')];
  const devices = [dev('A', 1), dev('B', 2), dev('C', 1)];
  assert.deepEqual(devicesOfStation(devices, stations, '1').map((d) => d.device_name), ['A', 'C']);
  assert.deepEqual(devicesOfStation(devices, stations, '2').map((d) => d.device_name), ['B']);
  assert.deepEqual(devicesOfStation(devices, stations, '77'), []);
});

test('pickStation: remembered wins when it is an option', () => {
  const opts = [
    { key: '1', name: '甲', address: '', deviceCount: 2 },
    { key: '2', name: '乙', address: '', deviceCount: 1 },
  ];
  assert.equal(pickStation(opts, '2').key, '2');
});

test('pickStation: unknown remembered falls back to the first station that has devices', () => {
  const opts = [
    { key: '1', name: '空', address: '', deviceCount: 0 },
    { key: '2', name: '乙', address: '', deviceCount: 3 },
  ];
  assert.equal(pickStation(opts, '99').key, '2');
  assert.equal(pickStation(opts, undefined).key, '2');
});

test('pickStation: when every option is empty it still returns the first, and undefined for none', () => {
  const opts = [{ key: '1', name: '空', address: '', deviceCount: 0 }];
  assert.equal(pickStation(opts).key, '1');
  assert.equal(pickStation([]), undefined);
});

test('filterStations: trims, ignores case, searches name and address, empty query keeps all', () => {
  const opts = [
    { key: '1', name: 'Alpha 站', address: '中山路 1 号', deviceCount: 1 },
    { key: '2', name: '乙站', address: 'Beta Road', deviceCount: 1 },
  ];
  assert.equal(filterStations(opts, '').length, 2);
  assert.equal(filterStations(opts, '   ').length, 2);
  assert.deepEqual(filterStations(opts, ' alpha ').map((o) => o.key), ['1']);
  assert.deepEqual(filterStations(opts, 'beta').map((o) => o.key), ['2']);
  assert.deepEqual(filterStations(opts, '中山').map((o) => o.key), ['1']);
  assert.deepEqual(filterStations(opts, '没有'), []);
});

console.log(`${n} checks passed`);
