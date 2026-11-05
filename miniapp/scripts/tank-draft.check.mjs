// Run with: npm run check   (node --experimental-strip-types; pure logic only, no wx)
import assert from 'node:assert/strict';
import { DRAFT_FIELDS, draftKey, emptyDraft, readDraft, sameDraft, validateDraft } from '../miniprogram/utils/tankDraft.ts';

let n = 0;
const test = (name, fn) => {
  fn();
  n++;
  console.log('ok -', name);
};

test('fields in the order of the design draft (after colour and medium)', () => {
  assert.deepEqual(
    DRAFT_FIELDS.map((f) => f.key),
    ['density', 'height', 'capacity', 'highHigh', 'high', 'lowLow', 'low', 'waterHigh', 'waterLow', 'tempHigh', 'tempLow'],
  );
});

test('defaults are "not set", never 0', () => {
  const d = emptyDraft();
  assert.equal(d.color, -1);
  assert.equal(d.medium, '');
  for (const f of DRAFT_FIELDS) assert.equal(d.values[f.key], '');
});

test('the key isolates account, device and tank', () => {
  const a = draftKey('u1', 'ZCT01', 1);
  assert.notEqual(a, draftKey('u2', 'ZCT01', 1));
  assert.notEqual(a, draftKey('u1', 'ZCT02', 1));
  assert.notEqual(a, draftKey('u1', 'ZCT01', 2));
  assert.notEqual(draftKey('u1', 'A/1', 2), draftKey('u1', 'A', 12)); // device names are encoded, not joined raw
});

test('empty and 0 are different, 0 is a valid level', () => {
  assert.equal(validateDraft({ high: '0' }), null);
  assert.equal(validateDraft({ high: '' }), null);
});

test('density, height, capacity must be positive when given', () => {
  assert.deepEqual(validateDraft({ density: '0' }), { field: 'density', reason: 'positive' });
  assert.deepEqual(validateDraft({ capacity: '-5' }), { field: 'capacity', reason: 'positive' });
  assert.equal(validateDraft({ density: '0.84', height: '2500', capacity: '30000' }), null);
});

test('levels may not be negative, temperatures may', () => {
  assert.deepEqual(validateDraft({ low: '-1' }), { field: 'low', reason: 'nonNegative' });
  assert.equal(validateDraft({ tempLow: '-20', tempHigh: '45.5' }), null);
});

test('anything that is not a plain number is refused', () => {
  for (const bad of ['1e3', '12,5', 'abc', '1.', '.5', '--1']) {
    assert.deepEqual(validateDraft({ height: bad }), { field: 'height', reason: 'number' }, bad);
  }
});

test('a broken stored draft reads as fields not set, not invented numbers', () => {
  assert.equal(readDraft(null), null);
  assert.equal(readDraft('x'), null);
  const d = readDraft({ color: 'red', medium: 5, values: { height: 3000, density: '0.84' } });
  assert.equal(d.color, -1);
  assert.equal(d.medium, '');
  assert.equal(d.values.height, '');
  assert.equal(d.values.density, '0.84');
});

test('sameDraft ignores the save time', () => {
  const a = { ...emptyDraft(), savedAt: 1 };
  const b = { ...emptyDraft(), savedAt: 2 };
  assert.equal(sameDraft(a, b), true);
  assert.equal(sameDraft(a, { ...b, medium: '柴油' }), false);
});

console.log(`${n} tank-draft checks passed`);
