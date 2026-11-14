// Run with: npm run check   (node --experimental-strip-types; pure logic only, no wx)
// 罐表生成（tank-shape-edit「生成罐表」）：与设备端 EllipseTable 逐点一致。
// fixtures/tank-table-golden.json 由设备端 Java 代码（android/.../factory/EllipseTable.java + DataUtils.doubleFoment，
// 原样复制后用 JDK 运行）生成，输入见各 key：形状_各尺寸(mm)。
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { doubleFoment, generateTankTable, parseDims, previewRows, tableArgs, tableEcho, tableValid } from '../miniprogram/utils/tankTable.ts';

const golden = JSON.parse(readFileSync(new URL('./fixtures/tank-table-golden.json', import.meta.url), 'utf8'));

let n = 0;
const test = (name, fn) => {
  fn();
  n++;
  console.log('ok -', name);
};

for (const [name, want] of Object.entries(golden)) {
  test(`same table as the device: ${name}`, () => {
    const [shape, ...dims] = name.split('_');
    const values = Object.fromEntries(dims.map((d) => [d[0], d.slice(1)]));
    const parsed = parseDims(shape, values);
    assert.equal(parsed.ok, true);
    const got = generateTankTable(shape, parsed.mm);
    assert.deepEqual(got.heightMm, want.heightMm);
    assert.deepEqual(got.volumeL, want.volumeL);
  });
}

test('doubleFoment: 1 decimal, integer from 100000.0 up, half-even on exact ties', () => {
  assert.equal(doubleFoment(1234.56), 1234.6);
  assert.equal(doubleFoment(99999.94), 99999.9);
  assert.equal(doubleFoment(123456.7), 123457);
  assert.equal(doubleFoment(0.25), 0.2);
  assert.equal(doubleFoment(0.75), 0.8);
});

test('inputs: required, numeric, ≥ 100 mm; horizontal head R may be 0', () => {
  assert.deepEqual(parseDims('horizontal', { H: '2600', L: '', R: '0' }), { ok: false, key: 'tankEdit.dimRequired', params: { dim: 'L' } });
  assert.deepEqual(parseDims('horizontal', { H: 'abc', L: '8000', R: '0' }), { ok: false, key: 'tankEdit.dimInvalid', params: { dim: 'H' } });
  assert.deepEqual(parseDims('horizontal', { H: '99', L: '8000', R: '0' }), { ok: false, key: 'tankEdit.dimTooSmall', params: { dim: 'H' } });
  assert.deepEqual(parseDims('vertical', { H: '2500', R: '0' }), { ok: false, key: 'tankEdit.dimTooSmall', params: { dim: 'R' } });
  assert.deepEqual(parseDims('cuboid', { H: '20001', L: '1000', R: '1000' }), { ok: false, key: 'tankEdit.dimTooLarge', params: { dim: 'H' } });
  assert.equal(parseDims('horizontal', { H: '2600', L: '8000', R: '0' }).ok, true);
  assert.equal(parseDims('horizontal', { H: '-2600', L: '8000', R: '0' }).ok, false);
});

test('a table with NaN (device formula overshoots the diameter) is not valid', () => {
  for (const mm of [{ H: 2605, L: 8000, R: 0 }, { H: 200, L: 1000, R: 0 }]) {
    const table = generateTankTable('horizontal', mm);
    assert.ok(table.volumeL.some((v) => !Number.isFinite(v)), JSON.stringify(mm));
    assert.equal(tableValid(table), false);
  }
  assert.equal(tableValid(generateTankTable('horizontal', { H: 2600, L: 8000, R: 0 })), true);
  assert.equal(tableValid(generateTankTable('vertical', { H: 2500, R: 2000 })), true);
});

test('preview: every point under 200, sampled above, last point always kept', () => {
  const small = { heightMm: [10, 20, 30], volumeL: [1, 2, 3] };
  assert.equal(previewRows(small).length, 3);
  const big = { heightMm: Array.from({ length: 450 }, (_, i) => i), volumeL: Array.from({ length: 450 }, (_, i) => i) };
  const rows = previewRows(big);
  assert.ok(rows.length <= 201);
  assert.equal(rows[0].heightMm, 0);
  assert.equal(rows[rows.length - 1].heightMm, 449);
});

test('send args: H/L/R mapped to the protocol fields per shape (#49)', () => {
  assert.deepEqual(tableArgs('horizontal', { H: 2600, L: 8000, R: 0 }), { shape: 'horizontal', diameterMm: 2600, lengthMm: 8000, headMm: 0 });
  assert.deepEqual(tableArgs('vertical', { H: 2500, R: 2000 }), { shape: 'vertical', heightMm: 2500, diameterMm: 2000 });
  assert.deepEqual(tableArgs('cuboid', { H: 1500, L: 3000, R: 2000 }), { shape: 'cuboid', heightMm: 1500, lengthMm: 3000, widthMm: 2000 });
});

test('echo: only a later report counts; points and last point must match the receipt', () => {
  const detail = { pointCount: 3, maxHeightMm: 30, maxVolumeL: 4.5 };
  const cal = (reportedAt, h = [10, 20, 30], v = [1.5, 3, 4.5]) => ({ reportedAt, heightMm: h, volumeL: v });
  const done = '2026-11-14T10:00:00Z';
  assert.equal(tableEcho(undefined, detail, done), 'waiting');
  assert.equal(tableEcho(cal('2026-11-14T09:59:59Z'), detail, done), 'waiting');
  assert.equal(tableEcho(cal('2026-11-14T10:00:00Z'), detail, done), 'waiting');
  assert.equal(tableEcho(cal('2026-11-14T10:00:03Z'), detail, undefined), 'waiting');
  assert.equal(tableEcho(cal('2026-11-14T10:00:03Z'), detail, done), 'matched');
  assert.equal(tableEcho(cal('2026-11-14T10:00:03Z', [10, 20], [1.5, 3]), detail, done), 'mismatch');
  assert.equal(tableEcho(cal('2026-11-14T10:00:03Z', [10, 20, 30], [1.5, 3, 9]), detail, done), 'mismatch');
  assert.equal(tableEcho(cal('2026-11-14T10:00:03Z', [], []), detail, done), 'mismatch');
});

console.log(`tank-table: ${n} checks passed`);
