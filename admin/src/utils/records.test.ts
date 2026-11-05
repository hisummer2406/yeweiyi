import { describe, expect, it } from 'vitest';
import {
  DASH,
  defaultWindow,
  filterByDirection,
  filterRows,
  groupStripes,
  hasDegradation,
  num,
  PAGE_SIZE,
  summarizeAlarms,
  summarizeStock,
  summarizeSynced,
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
  truncated: false,
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
      limit: PAGE_SIZE,
      offset: 0,
    });
  });
  it('page N asks for offset (N-1)*PAGE_SIZE; nonsense pages are page 1', () => {
    const w = defaultWindow();
    expect(toRecordsQuery('D1', w, {}, 3).offset).toBe(2 * PAGE_SIZE);
    expect(toRecordsQuery('D1', w, {}, 0).offset).toBe(0);
    expect(toRecordsQuery('D1', w, {}, 2.7).offset).toBe(PAGE_SIZE);
  });
});

describe('tank and direction filter (#84)', () => {
  const w = defaultWindow(new Date('2026-10-07T12:00:00Z'));
  it('puts only the given filters on the query', () => {
    expect(
      toRecordsQuery('D1', w, { tank: 2, direction: 'OUT' }),
    ).toMatchObject({ tank: 2, direction: 'OUT' });
    const q = toRecordsQuery('D1', w, {});
    expect('tank' in q || 'direction' in q).toBe(false);
  });
  it('filterRows drops other tanks and directions (an older cloud ignores the parameters)', () => {
    const rows = [
      { ...ev('IN'), tank: 1 },
      { ...ev('OUT'), tank: 2 },
      { ...ev('IN'), tank: 2 },
    ];
    expect(filterRows(rows, { tank: 2 })).toHaveLength(2);
    expect(filterRows(rows, { tank: 2, direction: 'IN' })).toHaveLength(1);
    expect(filterRows(rows, {})).toHaveLength(3);
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
  it('more pages is not a notice (the pager handles it)', () => {
    expect(summarizeAlarms({ ...alarms(PAGE_SIZE), truncated: true })).toEqual(
      [],
    );
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

  it('legacySkipped, invalidSource and partial are warnings with counts; truncated (more pages) is not a notice', () => {
    const n = summarizeStock(
      stock({
        legacySkipped: 4,
        invalidSource: 2,
        partial: true,
        truncated: true,
      }),
    );
    expect(ids(n)).toEqual(['partial', 'legacySkipped', 'invalidSource']);
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

describe('num', () => {
  it('missing is a dash, 0 stays 0, at most two decimals', () => {
    expect(num(undefined)).toBe(DASH);
    expect(num(null)).toBe(DASH);
    expect(num(Number.NaN)).toBe(DASH);
    expect(num(0)).toBe('0');
    expect(num(1546.4651607837066)).toBe('1546.47');
    expect(num(-3.2)).toBe('-3.2');
  });
});

describe('summarizeSynced (cloud#85 §4)', () => {
  const base: API.SyncedRecordList<unknown> = {
    rows: [],
    truncated: false,
    partial: false,
    unparsable: 0,
    syncStatus: 'SYNCED',
  };
  const ids = (p: Partial<API.SyncedRecordList<unknown>>) => {
    return summarizeSynced({ ...base, ...p }).map((n) => n.id);
  };

  it('only SYNCED without problems may say "no records"', () => {
    expect(hasDegradation(summarizeSynced(base))).toBe(false);
    for (const s of ['UNSUPPORTED', 'SYNCING', 'SOMETHING_NEW', undefined]) {
      expect(hasDegradation(summarizeSynced({ ...base, syncStatus: s }))).toBe(
        true,
      );
    }
  });

  it('maps each status to its own notice', () => {
    expect(ids({ syncStatus: 'UNSUPPORTED' })).toEqual(['unsupported']);
    expect(ids({ syncStatus: 'SYNCING' })).toEqual(['syncing']);
    expect(ids({ syncStatus: 'NEW' })).toEqual(['syncUnknown']);
    expect(ids({ syncStatus: undefined })).toEqual(['syncUnknown']);
    expect(ids({ partial: true })).toEqual(['syncing']);
  });

  it('reports unparsable with its count; more pages is not a notice', () => {
    const n = summarizeSynced({ ...base, unparsable: 3, truncated: true });
    expect(n.map((x) => x.id)).toEqual(['unparsable']);
    expect(n[0].values.count).toBe('3');
  });
});

describe('groupStripes', () => {
  it('rows of one record share a stripe; adjacent records alternate', () => {
    const k = (r: string) => r;
    expect(groupStripes(['a', 'a', 'b', 'c', 'c'], k)).toEqual([0, 0, 1, 0, 0]);
    expect(groupStripes(['', ''], k)).toEqual([0, 1]);
    expect(groupStripes([], k)).toEqual([]);
  });
});
