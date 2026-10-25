// Run with: npm run check   (node --experimental-strip-types; pure logic only, no wx)
import assert from 'node:assert/strict';
import { parseTankParam, pickTank, visibleTanks } from '../miniprogram/utils/tankPick.ts';

let n = 0;
const test = (name, fn) => {
  fn();
  n++;
  console.log('ok -', name);
};

test('visible tanks are ascending and unique', () => {
  assert.deepEqual(visibleTanks([5, 2, 2]), [2, 5]);
});

test('granted_tanks narrows the snapshot; null or missing does not', () => {
  assert.deepEqual(visibleTanks([1, 2, 3], [1]), [1]);
  assert.deepEqual(visibleTanks([1, 2, 3], null), [1, 2, 3]);
  assert.deepEqual(visibleTanks([1, 2, 3]), [1, 2, 3]);
});

test('tank param is a non-negative integer or nothing', () => {
  assert.equal(parseTankParam('3'), 3);
  assert.equal(parseTankParam('0'), 0);
  assert.equal(parseTankParam(undefined), undefined);
  assert.equal(parseTankParam(''), undefined);
  assert.equal(parseTankParam('1a'), undefined);
  assert.equal(parseTankParam('-1'), undefined);
});

test('the tapped tank is selected', () => {
  assert.deepEqual(pickTank([1, 3], 3), { kind: 'tank', tank: 3 });
});

test('no tank asked (scan): the lowest visible tank', () => {
  assert.deepEqual(pickTank([2, 5]), { kind: 'tank', tank: 2 });
  assert.deepEqual(pickTank([5, 2]), { kind: 'tank', tank: 2 });
});

test('asked tank not visible: says so and falls back to the first visible', () => {
  assert.deepEqual(pickTank([2], 4), { kind: 'tank', tank: 2, hidden: 4 });
});

test('no visible tank at all', () => {
  assert.deepEqual(pickTank([]), { kind: 'none' });
  assert.deepEqual(pickTank([], 4), { kind: 'none', hidden: 4 });
});

console.log(`${n} tank-pick checks passed`);
