import { describe, expect, it } from 'vitest';
import {
  defaultWindow,
  filterByDirection,
  hasDegradation,
  RECORDS_LIMIT,
  summarizeAlarms,
  summarizeStock,
  toRecordsQuery,
} from './records';

const stock = (p: Partial<API.StockList> = {}): API.StockList => ({
  rows: [],
  parsed: 0,
  unparsable: 0,
  legacySkipped: 0,
  invalidSource: 0,
  folded: 0,
  truncated: false,
  partial: false,
  ...p,
});
const ev = (direction: string): API.StockEvent =>
  ({ eventId: direction, direction }) as API.StockEvent;
const alarms = (n: number, partial = false): API.AlarmList => ({
  rows: Array.from({ length: n }, () => ({}) as API.Alarm),
  partial,
});
const ids = (ns: { id: string }[]) => ns.map((n) => n.id);

describe('window', () => {
  it('defaults to the last 24 hours ending now', () => {
    const now = new Date('2026-10-07T12:00:00.000Z');
    const w = defaultWindow(now);
    expect(w.end).toEqual(now);
    expect(w.begin.toISOString()).toBe('2026-10-06T12:00:00.000Z');
  });

  it('builds RFC3339 UTC query parameters', () => {
    const q = toRecordsQuery(
      'D1',
      defaultWindow(new Date('2026-10-07T12:00:00Z')),
    );
    expect(q).toEqual({
      device: 'D1',
      beginTime: '2026-10-06T12:00:00.000Z',
      endTime: '2026-10-07T12:00:00.000Z',
    });
  });
});

describe('filterByDirection', () => {
  const rows = [ev('IN'), ev('OUT'), ev('IN')];
  it('keeps only the requested direction', () => {
    expect(filterByDirection(rows, 'IN')).toHaveLength(2);
    expect(filterByDirection(rows, 'OUT')).toHaveLength(1);
  });
  it('does not filter without a direction', () => {
    expect(filterByDirection(rows)).toHaveLength(3);
  });
});

describe('summarizeAlarms', () => {
  it('has no notice for a complete short list', () => {
    expect(summarizeAlarms(alarms(37))).toEqual([]);
  });
  it('warns when the response is partial', () => {
    expect(ids(summarizeAlarms(alarms(1, true)))).toEqual(['partial']);
  });
  it('warns of truncation exactly at the limit, not below', () => {
    expect(ids(summarizeAlarms(alarms(RECORDS_LIMIT - 1)))).toEqual([]);
    const n = summarizeAlarms(alarms(RECORDS_LIMIT));
    expect(ids(n)).toEqual(['truncated']);
    expect(n[0].values.limit).toBe('500');
  });
  it('an empty complete window is not degraded', () => {
    expect(hasDegradation(summarizeAlarms(alarms(0)))).toBe(false);
  });
  it('an empty partial window IS degraded (must not read as "no alarms")', () => {
    expect(hasDegradation(summarizeAlarms(alarms(0, true)))).toBe(true);
  });
});

describe('summarizeStock', () => {
  it('has no notice for a clean list', () => {
    expect(summarizeStock(stock({ rows: [ev('IN')], parsed: 1 }))).toEqual([]);
  });

  it('a 502 degraded result is an error carrying the device and sample', () => {
    const n = summarizeStock(
      stock({
        unparsable: 3,
        degraded: {
          code: 'STOCK_EVENT_FORMAT_UNSUPPORTED',
          device: 'D1',
          sample: 'DetailInoutRecord{}',
        },
      }),
    );
    expect(n[0]).toMatchObject({ level: 'error', id: 'firmware' });
    expect(n[0].values).toEqual({
      count: '3',
      device: 'D1',
      sample: 'DetailInoutRecord{}',
    });
  });

  it('unparsable>0 on a 200 is an error too', () => {
    expect(summarizeStock(stock({ unparsable: 2 }))[0]).toMatchObject({
      level: 'error',
      id: 'firmware',
    });
  });

  it('legacySkipped, invalidSource, partial and truncated are warnings with counts', () => {
    const n = summarizeStock(
      stock({
        legacySkipped: 4,
        invalidSource: 2,
        partial: true,
        truncated: true,
      }),
    );
    expect(ids(n)).toEqual([
      'partial',
      'legacySkipped',
      'invalidSource',
      'truncated',
    ]);
    expect(n.every((x) => x.level === 'warning')).toBe(true);
    expect(n.find((x) => x.id === 'legacySkipped')?.values.count).toBe('4');
    expect(n.find((x) => x.id === 'invalidSource')?.values.count).toBe('2');
  });

  it('folded is only information', () => {
    const n = summarizeStock(stock({ folded: 5 }));
    expect(n).toEqual([
      { level: 'info', id: 'folded', values: { count: '5' } },
    ]);
    expect(hasDegradation(n)).toBe(false);
  });

  it('the error comes first', () => {
    const n = summarizeStock(stock({ partial: true, unparsable: 1 }));
    expect(n[0].id).toBe('firmware');
  });

  it.each([
    ['degraded', { degraded: { code: 'X' } }],
    ['unparsable', { unparsable: 1 }],
    ['legacySkipped', { legacySkipped: 1 }],
    ['partial', { partial: true }],
  ])('an empty list with %s is degraded, not "no records"', (_n, p) => {
    expect(
      hasDegradation(summarizeStock(stock(p as Partial<API.StockList>))),
    ).toBe(true);
  });

  it('an empty clean list is not degraded', () => {
    expect(hasDegradation(summarizeStock(stock()))).toBe(false);
  });
});
