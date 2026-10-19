// Run with: npm run check   (node --experimental-strip-types; pure logic only, no wx)
import assert from 'node:assert/strict';
import { formatLocalTime } from '../miniprogram/utils/format.ts';

const test = (name, fn) => {
  fn();
  console.log('ok -', name);
};
// Local-time instants (the checks must not depend on the machine's zone).
const at = (y, mo, d, h = 0, mi = 0) => new Date(y, mo - 1, d, h, mi, 30);
const iso = (d) => d.toISOString();
const now = at(2026, 10, 19, 15, 0);

test('today and yesterday are words, with a minute-precision clock', () => {
  assert.equal(formatLocalTime(iso(at(2026, 10, 19, 9, 5)), 'zh', now), '今天 09:05');
  assert.equal(formatLocalTime(iso(at(2026, 10, 18, 23, 59)), 'en', now), 'Yesterday 23:59');
  assert.equal(formatLocalTime(iso(at(2026, 10, 19, 0, 0)), 'es', now), 'Hoy 00:00');
});

test('earlier this year drops the year', () => {
  assert.equal(formatLocalTime(iso(at(2026, 10, 7, 14, 5)), 'zh', now), '10月7日 14:05');
  assert.equal(formatLocalTime(iso(at(2026, 10, 7, 14, 5)), 'en', now), 'Oct 7 14:05');
  assert.equal(formatLocalTime(iso(at(2026, 10, 7, 14, 5)), 'es', now), '7 oct 14:05');
});

test('another year keeps the year', () => {
  assert.equal(formatLocalTime(iso(at(2025, 12, 31, 8, 0)), 'zh', now), '2025年12月31日 08:00');
  assert.equal(formatLocalTime(iso(at(2025, 12, 31, 8, 0)), 'en', now), 'Dec 31, 2025 08:00');
  assert.equal(formatLocalTime(iso(at(2025, 12, 31, 8, 0)), 'es', now), '31 dic 2025 08:00');
});

test('a future instant is shown by date, never as a relative word', () => {
  assert.equal(formatLocalTime(iso(at(2026, 10, 20, 1, 0)), 'zh', now), '10月20日 01:00');
});

test('empty is empty, junk is returned unchanged, unknown locale uses English', () => {
  assert.equal(formatLocalTime('', 'zh', now), '');
  assert.equal(formatLocalTime('not a time', 'zh', now), 'not a time');
  assert.equal(formatLocalTime(iso(at(2026, 10, 19, 9, 5)), 'fr', now), 'Today 09:05');
});
