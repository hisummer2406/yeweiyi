// Run with: npm run check   (node --experimental-strip-types; pure logic only, no wx)
import assert from 'node:assert/strict';
import { zh } from '../miniprogram/locales/zh.ts';
import {
  COLUMNS,
  DASH,
  SUPPORTED,
  alarmCells,
  alarmRowKey,
  directionOf,
  num,
  stockCells,
  stockRowKey,
} from '../miniprogram/utils/recordColumns.ts';

let n = 0;
const test = (name, fn) => {
  fn();
  n++;
  console.log('ok -', name);
};

const header = (type) => COLUMNS[type].map((c) => `${zh[c.titleKey]}${c.unit ? `(${c.unit})` : ''}`);

// The order the device display uses (miniapp#17). Stock level / water are millimetres, not millilitres.
test('column order matches the display', () => {
  assert.deepEqual(header('in'), ['罐号', '介质', '入库时间', '前高(mm)', '前量(L)', '后高(mm)', '后量(L)', '入库量(L)', '入库量(kg)']);
  assert.deepEqual(header('out'), ['罐号', '介质', '出库时间', '前高(mm)', '前量(L)', '后高(mm)', '后量(L)', '出库量(L)', '出库量(kg)']);
  assert.deepEqual(header('alarm'), ['罐号', '介质', '报警信息', '报警时间']);
  assert.deepEqual(header('stock'), ['罐号', '介质', '库存生成时间', '液位(mm)', '容积(L)', '水高(mm)', '温度(℃)', '质量(kg)']);
  assert.deepEqual(header('shift'), [
    '罐号', '介质', '交班时间', '接班时间', '前高(mm)', '前量(L)', '后高(mm)', '后量(L)', '出库量(L)', '出库量(kg)',
  ]);
});

test('stock and shift are not open yet', () => {
  assert.equal(SUPPORTED.stock, false);
  assert.equal(SUPPORTED.shift, false);
  assert.equal(SUPPORTED.in && SUPPORTED.out && SUPPORTED.alarm, true);
});

test('direction of the in/out pages', () => {
  assert.equal(directionOf('in'), 'IN');
  assert.equal(directionOf('out'), 'OUT');
  assert.equal(directionOf('alarm'), undefined);
});

test('a missing number is a dash, never 0', () => {
  assert.equal(num(undefined), DASH);
  assert.equal(num(null), DASH);
  assert.equal(num(NaN), DASH);
  assert.equal(num(0), '0');
  assert.equal(num(1546.4651607837066), '1546.47');
});

const row = {
  eventId: 'e1', tank: 2, direction: 'IN', oilType: '92#', source: 'AUTO',
  beginTime: 'b', endTime: 'e', beginLevelMm: 100, endLevelMm: 900.456, beginVolumeL: 1000, endVolumeL: 9000,
  beginTempC: 20, endTempC: 21, beginWeightKg: 700, endWeightKg: 6400, netVolumeL: 8000, deliveredVolumeL: 7950, time: 'T',
};

test('in/out cells: delivered volume, kg left as a dash (not the weight difference)', () => {
  const cells = stockCells(row, (iso) => `t(${iso})`);
  assert.equal(cells.length, COLUMNS.in.length - 1);
  assert.deepEqual(cells, ['92#', 't(T)', '100', '1000', '900.46', '9000', '7950', DASH]);
});

test('in/out cells: no oil type is a dash', () => {
  assert.equal(stockCells({ ...row, oilType: '' }, (x) => x)[0], DASH);
});

test('alarm cells: medium is a dash, not the current oil type', () => {
  const a = { alarmId: 'a', tank: 1, type: 'HIGH_LEVEL', severity: 'MAJOR', status: 'ACTIVE', text: 'x', time: 'T' };
  const cells = alarmCells(a, '高液位: x', (x) => x);
  assert.equal(cells.length, COLUMNS.alarm.length - 1);
  assert.deepEqual(cells, [DASH, '高液位: x', 'T']);
});

test('row keys tell ACTIVE from CLEARED and IN from OUT', () => {
  const a = { alarmId: '', tank: 1, type: 'HIGH_LEVEL', severity: 'MAJOR', status: 'ACTIVE', text: '', time: 'T' };
  assert.notEqual(alarmRowKey(a), alarmRowKey({ ...a, status: 'CLEARED' }));
  assert.notEqual(stockRowKey(row), stockRowKey({ ...row, direction: 'OUT' }));
});

console.log(`${n} record-columns checks passed`);
