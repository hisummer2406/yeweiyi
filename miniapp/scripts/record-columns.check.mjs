// Run with: npm run check   (node --experimental-strip-types; pure logic only, no wx)
import assert from 'node:assert/strict';
import { zh } from '../miniprogram/locales/zh.ts';
import { en } from '../miniprogram/locales/en.ts';
import {
  COLUMNS,
  DASH,
  alarmCells,
  alarmRowKey,
  directionOf,
  groupOf,
  inventoryCells,
  inventoryRowKey,
  isRecordListResponse,
  mediumText,
  num,
  oilTypeKey,
  shiftCells,
  shiftRowKey,
  stockCells,
  stockRowKey,
  syncSummary,
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

const zt = (k) => zh[k] ?? k;
const f = { time: (iso) => `t(${iso})`, tr: zt };

test('medium: builtin index in the app language, else the device text, else a dash', () => {
  assert.equal(oilTypeKey(0), null);
  assert.equal(oilTypeKey(32), null);
  assert.equal(oilTypeKey(18), 'oil.18');
  assert.equal(mediumText(18, 'PG', (k) => en[k]), 'Propylene Glycol');
  assert.equal(mediumText(31, '', zt), '西排水');
  assert.equal(mediumText(22, '', zt), DASH); // old-firmware custom slot (#11)
  assert.equal(mediumText(20, 'Soy Oil', zt), '大豆油');
  assert.equal(mediumText(33, '自定义B', zt), '自定义B');
  assert.equal(oilTypeKey(1.5), null);
  assert.equal(oilTypeKey(null), null);
  assert.equal(mediumText(5, 'whatever', zt), '92#');
  // The device's English list is misaligned (index 2 reads "Sulfuric Acid"): the index decides.
  assert.equal(mediumText(2, 'Sulfuric Acid', (k) => en[k]), '-10# Diesel');
  assert.equal(mediumText(10, 'Kerosene', zt), '米糠油');
  assert.equal(mediumText(20, '自定义油', zt), '自定义油');
  assert.equal(mediumText(0, '', zt), DASH);
  assert.equal(mediumText(null, null, zt), DASH);
  assert.equal(mediumText(undefined, '95#', zt), '95#');
});

test('every builtin oil index has a name in every table', () => {
  for (let i = 1; i <= 17; i++) {
    assert.ok(zh[`oil.${i}`], `zh oil.${i}`);
    assert.ok(en[`oil.${i}`], `en oil.${i}`);
  }
});

const row = {
  eventId: 'e1', tank: 2, direction: 'IN', oilType: '92#', source: 'AUTO',
  beginTime: 'b', endTime: 'e', beginLevelMm: 100, endLevelMm: 900.456, beginVolumeL: 1000, endVolumeL: 9000,
  beginTempC: 20, endTempC: 21, beginWeightKg: 700, endWeightKg: 6400, netVolumeL: 8000, deliveredVolumeL: 7950, time: 'T',
};

test('in/out cells: delivered volume; kg only from deliveredWeightKg, never the weight difference', () => {
  const cells = stockCells(row, f);
  assert.equal(cells.length, COLUMNS.in.length - 1);
  assert.deepEqual(cells, ['92#', 't(T)', '100', '1000', '900.46', '9000', '7950', DASH]);
  assert.equal(stockCells({ ...row, deliveredWeightKg: null }, f)[7], DASH);
  assert.equal(stockCells({ ...row, deliveredWeightKg: 5600.123 }, f)[7], '5600.12');
  assert.equal(stockCells({ ...row, oilTypeIndex: 6 }, f)[0], '95#');
});

test('in/out cells: no oil type is a dash', () => {
  assert.equal(stockCells({ ...row, oilType: '' }, f)[0], DASH);
});

test('alarm cells: medium from the alarm itself; none is a dash', () => {
  const a = { alarmId: 'a', tank: 1, type: 'HIGH_LEVEL', severity: 'MAJOR', status: 'ACTIVE', text: 'x', time: 'T' };
  const cells = alarmCells(a, '高液位: x', f);
  assert.equal(cells.length, COLUMNS.alarm.length - 1);
  assert.deepEqual(cells, [DASH, '高液位: x', 't(T)']);
  assert.equal(alarmCells({ ...a, oilType: null, oilTypeIndex: null }, '', f)[0], DASH);
  assert.equal(alarmCells({ ...a, oilType: '0#', oilTypeIndex: 1 }, '', f)[0], '0#');
});

const shift = {
  shiftId: 's1', status: 'CLOSED', beginTime: 'B', endTime: 'E', tank: 2, oilType: '92#', oilTypeIndex: 5,
  beginLevelMm: 1200, beginVolumeL: 15000.5, endLevelMm: 1100, endVolumeL: 13800, outVolumeL: 1200.5, outWeightKg: 890.4,
};

test('shift cells: handover (end) before takeover (begin), as on the display', () => {
  const cells = shiftCells(shift, f);
  assert.equal(cells.length, COLUMNS.shift.length - 1);
  assert.deepEqual(cells, ['92#', 't(E)', 't(B)', '1200', '15000.5', '1100', '13800', '1200.5', '890.4']);
});

test('open shift: no handover time and no end figures, all dashes (never 0)', () => {
  const open = { ...shift, status: 'OPEN', endTime: null, endLevelMm: null, endVolumeL: null, outVolumeL: null, outWeightKg: null };
  assert.deepEqual(shiftCells(open, f), ['92#', DASH, 't(B)', '1200', '15000.5', DASH, DASH, DASH, DASH]);
  // A tank added mid-shift has no begin figures; an older record has no medium at all.
  const late = { ...shift, oilType: null, oilTypeIndex: null, beginLevelMm: null, beginVolumeL: null };
  assert.deepEqual(shiftCells(late, f).slice(0, 5), [DASH, 't(E)', 't(B)', DASH, DASH]);
});

const snap = {
  recordId: 'r1', time: 'T', trigger: 'SCHEDULED', tank: 3, oilType: 'Diesel', oilTypeIndex: null,
  levelMm: 1500.555, volumeL: 20000, waterLevelMm: 0, tempC: -3.2, weightKg: null,
};

test('stock snapshot cells: mm levels, 0 water stays 0, missing weight is a dash', () => {
  const cells = inventoryCells(snap, f);
  assert.equal(cells.length, COLUMNS.stock.length - 1);
  assert.deepEqual(cells, ['Diesel', 't(T)', '1500.56', '20000', '0', '-3.2', DASH]);
});

test('shift and snapshot keys: one per (record, tank); an open shift keeps its key when it closes', () => {
  assert.equal(shiftRowKey({ ...shift, status: 'OPEN', endTime: null }), shiftRowKey(shift));
  assert.notEqual(shiftRowKey(shift), shiftRowKey({ ...shift, tank: 3 }));
  assert.notEqual(inventoryRowKey(snap), inventoryRowKey({ ...snap, tank: 4 }));
  assert.equal(groupOf('shift', shift), 's1');
  assert.equal(groupOf('stock', snap), 'r1');
  assert.equal(groupOf('alarm', shift), '');
});

const env = (o) => ({ rows: [], truncated: false, partial: false, unparsable: 0, syncStatus: 'SYNCED', ...o });

test('sync status: only SYNCED may say "no records"', () => {
  assert.deepEqual(syncSummary(env({})), { unsupported: false, canSayEmpty: true, notes: [] });
  const un = syncSummary(env({ syncStatus: 'UNSUPPORTED' }));
  assert.equal(un.unsupported, true);
  assert.equal(un.canSayEmpty, false);
  const sy = syncSummary(env({ syncStatus: 'SYNCING', partial: true }));
  assert.equal(sy.canSayEmpty, false);
  assert.deepEqual(sy.notes, [{ key: 'records.syncing' }]);
  // A status this app does not know (a newer cloud): cannot claim the list is whole or empty.
  const x = syncSummary(env({ syncStatus: 'SOMETHING_NEW' }));
  assert.equal(x.canSayEmpty, false);
  assert.equal(x.unsupported, false);
  assert.deepEqual(x.notes, [{ key: 'records.syncUnknown' }]);
});

test('sync status: unparsable records always note the list may be incomplete', () => {
  assert.deepEqual(syncSummary(env({ unparsable: 3 })).notes, [{ key: 'records.unparsable', params: { n: 3 } }]);
  assert.equal(syncSummary(env({ unparsable: 3 })).canSayEmpty, true);
  assert.equal(isRecordListResponse(env({})), true);
  assert.equal(isRecordListResponse({ error: 'x' }), false);
  assert.equal(isRecordListResponse('404 page not found'), false);
});

test('row keys tell ACTIVE from CLEARED and IN from OUT', () => {
  const a = { alarmId: '', tank: 1, type: 'HIGH_LEVEL', severity: 'MAJOR', status: 'ACTIVE', text: '', time: 'T' };
  assert.notEqual(alarmRowKey(a), alarmRowKey({ ...a, status: 'CLEARED' }));
  assert.notEqual(stockRowKey(row), stockRowKey({ ...row, direction: 'OUT' }));
});

console.log(`${n} record-columns checks passed`);
