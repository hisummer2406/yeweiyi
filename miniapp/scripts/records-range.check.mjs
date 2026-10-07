// Run with: npm run check   (node --experimental-strip-types; pure logic only, no wx)
// The phone's local zone matters for custom date ranges and for time display, so pin one.
process.env.TZ = 'Asia/Shanghai';

import assert from 'node:assert/strict';
import {
  FILTERED_PAGE_SIZE,
  MAX_AUTO_PAGES,
  MIN_ADDED_AFTER_FILTER,
  PAGE_SIZE,
  RANGE_PRESETS,
  alarmQuery,
  customRangeProblem,
  formatDate,
  resolveWindow,
  shouldAutoContinue,
  splitAlarmPage,
  stockQuery,
} from '../miniprogram/utils/recordsRange.ts';
import { formatLocalTime } from '../miniprogram/utils/format.ts';
import { summarizeOilInOut } from '../miniprogram/utils/oilInOut.ts';

let n = 0;
const test = (name, fn) => {
  fn();
  n++;
  console.log('ok -', name);
};

// 2026-10-07 18:30:00 in Asia/Shanghai == 10:30:00Z
const NOW = new Date('2026-10-07T10:30:00Z');

test('presets roll back from now', () => {
  assert.deepEqual(RANGE_PRESETS.map((p) => p.key), ['24h', '7d', '30d']);
  assert.deepEqual(resolveWindow({ key: '24h' }, NOW), {
    beginTime: '2026-10-06T10:30:00Z',
    endTime: '2026-10-07T10:30:00Z',
  });
  assert.equal(resolveWindow({ key: '7d' }, NOW).beginTime, '2026-09-30T10:30:00Z');
  assert.equal(resolveWindow({ key: '30d' }, NOW).beginTime, '2026-09-07T10:30:00Z');
});

test('custom range uses local midnight of the start day and the end of the end day', () => {
  // 10-03 00:00 +08:00 == 10-02 16:00Z ; end of 10-05 == 10-06 00:00 +08:00 == 10-05 16:00Z
  assert.deepEqual(resolveWindow({ key: 'custom', startDate: '2026-10-03', endDate: '2026-10-05' }, NOW), {
    beginTime: '2026-10-02T16:00:00Z',
    endTime: '2026-10-05T16:00:00Z',
  });
});

test('a custom range that ends today is clamped to now, not to tomorrow midnight', () => {
  const w = resolveWindow({ key: 'custom', startDate: '2026-10-07', endDate: '2026-10-07' }, NOW);
  assert.equal(w.beginTime, '2026-10-06T16:00:00Z');
  assert.equal(w.endTime, '2026-10-07T10:30:00Z');
});

test('an incomplete, reversed, malformed or future custom range has no window and says why', () => {
  const cases = [
    [{ key: 'custom' }, 'incomplete'],
    [{ key: 'custom', startDate: '2026-10-03' }, 'incomplete'],
    [{ key: 'custom', startDate: '2026-10-05', endDate: '2026-10-03' }, 'order'],
    [{ key: 'custom', startDate: '2026-13-40', endDate: '2026-10-03' }, 'invalid'],
    [{ key: 'custom', startDate: 'abc', endDate: '2026-10-03' }, 'invalid'],
    [{ key: 'custom', startDate: '2026-10-08', endDate: '2026-10-09' }, 'future'],
  ];
  for (const [range, problem] of cases) {
    assert.equal(customRangeProblem(range, NOW), problem, JSON.stringify(range));
    assert.equal(resolveWindow(range, NOW), null, JSON.stringify(range));
  }
  assert.equal(customRangeProblem({ key: 'custom', startDate: '2026-10-03', endDate: '2026-10-05' }, NOW), '');
  assert.equal(customRangeProblem({ key: '7d' }, NOW), '');
});

test('formatDate gives the local calendar day', () => {
  assert.equal(formatDate(NOW), '2026-10-07');
  assert.equal(formatDate(new Date('2026-10-06T17:00:00Z')), '2026-10-07'); // 01:00 local next day
});

const WIN = { beginTime: 'B', endTime: 'E' };

test('alarm pages ask for one extra row to know whether more exist, and step by the page size', () => {
  assert.deepEqual(alarmQuery('D1', WIN, 0), {
    device: 'D1', beginTime: 'B', endTime: 'E', limit: PAGE_SIZE + 1, offset: 0, sort: false,
  });
  assert.equal(alarmQuery('D1', WIN, 3).offset, 3 * PAGE_SIZE);
});

test('splitAlarmPage drops the probe row and reports hasMore', () => {
  const rows = Array.from({ length: PAGE_SIZE + 1 }, (_, i) => i);
  const full = splitAlarmPage(rows);
  assert.equal(full.items.length, PAGE_SIZE);
  assert.equal(full.hasMore, true);
  assert.deepEqual(splitAlarmPage([1, 2, 3]), { items: [1, 2, 3], hasMore: false });
  assert.deepEqual(splitAlarmPage([]), { items: [], hasMore: false });
  assert.equal(splitAlarmPage(rows.slice(0, PAGE_SIZE - 1)).hasMore, false);
  // exactly a full page: the cloud may have skipped an unreadable row from the 51 it was asked for, so
  // this must still count as "more"
  const exact = rows.slice(0, PAGE_SIZE);
  assert.deepEqual(splitAlarmPage(exact), { items: exact, hasMore: true });
});

test('stock pages use a bigger page and a matching offset step when a direction filter is on', () => {
  assert.deepEqual(stockQuery('D1', WIN, 2, false), {
    device: 'D1', beginTime: 'B', endTime: 'E', limit: PAGE_SIZE, offset: 2 * PAGE_SIZE, sort: false,
  });
  const filtered = stockQuery('D1', WIN, 2, true);
  assert.equal(filtered.limit, FILTERED_PAGE_SIZE);
  assert.equal(filtered.offset, 2 * FILTERED_PAGE_SIZE);
});

test('auto-continue only applies to a filtered list that is still short and has more, capped per run', () => {
  assert.equal(shouldAutoContinue(false, 0, true, 1), false); // unfiltered never auto-continues
  assert.equal(shouldAutoContinue(true, 0, true, 1), true);
  assert.equal(shouldAutoContinue(true, MIN_ADDED_AFTER_FILTER - 1, true, 1), true);
  assert.equal(shouldAutoContinue(true, MIN_ADDED_AFTER_FILTER, true, 1), false);
  assert.equal(shouldAutoContinue(true, 0, false, 1), false); // nothing more to fetch
  assert.equal(shouldAutoContinue(true, 0, true, MAX_AUTO_PAGES), false); // cap reached
});

test('formatLocalTime renders the local wall clock and leaves junk alone', () => {
  assert.equal(formatLocalTime('2026-10-07T10:13:29.788Z'), '2026-10-07 18:13:29');
  assert.equal(formatLocalTime('2026-10-06T16:00:00Z'), '2026-10-07 00:00:00');
  assert.equal(formatLocalTime(''), '');
  assert.equal(formatLocalTime('not a time'), 'not a time');
});

const stockRes = (over = {}) => ({
  rows: [{ direction: 'IN' }, { direction: 'OUT' }],
  parsed: 2, unparsable: 0, legacySkipped: 0, invalidSource: 0, folded: 0, truncated: false, partial: false,
  ...over,
});

test('the "more than one page" note is dropped when the list pages, kept otherwise', () => {
  const res = stockRes({ truncated: true });
  assert.match(summarizeOilInOut(res, '').note, /超过单页上限/);
  assert.equal(summarizeOilInOut(res, '', { paged: true }).note, '');
});

test('the unreadable-records note does not quote a per-page count when paging', () => {
  const res = stockRes({ unparsable: 2, parsed: 37 });
  assert.match(summarizeOilInOut(res, '').note, /仅展示可读取的 37 条/);
  const paged = summarizeOilInOut(res, '', { paged: true }).note;
  assert.doesNotMatch(paged, /37/);
  assert.match(paged, /可能不完整/);
});

test('window-level notes survive paging', () => {
  const res = stockRes({ truncated: true, unparsable: 2, partial: true });
  const note = summarizeOilInOut(res, '', { paged: true }).note;
  assert.match(note, /无法解析/);
  assert.match(note, /仍在同步/);
  assert.doesNotMatch(note, /超过单页上限/);
});

console.log(`${n} checks passed`);
