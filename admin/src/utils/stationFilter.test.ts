import { describe, expect, it } from 'vitest';
import {
  matchesStation,
  parseStationParam,
  sectionByStation,
  stationFilterAvailable,
  stationParam,
} from './stationFilter';

describe('parseStationParam', () => {
  it('defaults to all', () => {
    expect(parseStationParam(null, [1, 2])).toBe('all');
    expect(parseStationParam(undefined, [1, 2])).toBe('all');
    expect(parseStationParam('', [1, 2])).toBe('all');
  });
  it('accepts none and visible ids', () => {
    expect(parseStationParam('none', [])).toBe('none');
    expect(parseStationParam('2', [1, 2])).toBe(2);
  });
  it('falls back to all for invisible ids and junk', () => {
    expect(parseStationParam('9', [1, 2])).toBe('all');
    expect(parseStationParam('abc', [1, 2])).toBe('all');
    expect(parseStationParam('1.5', [1, 2])).toBe('all');
    expect(parseStationParam('-1', [1, 2])).toBe('all');
  });
});

describe('stationParam', () => {
  it('omits all', () => {
    expect(stationParam('all')).toBeUndefined();
    expect(stationParam('none')).toBe('none');
    expect(stationParam(3)).toBe('3');
  });
});

describe('matchesStation', () => {
  it('matches by station, ungrouped, or everything', () => {
    expect(matchesStation({ station_id: 3 }, 3)).toBe(true);
    expect(matchesStation({ station_id: 4 }, 3)).toBe(false);
    expect(matchesStation({ station_id: null }, 'none')).toBe(true);
    expect(matchesStation({ station_id: 3 }, 'none')).toBe(false);
    expect(matchesStation({ station_id: 3 }, 'all')).toBe(true);
  });
  it('a row without station_id (old cloud) is not "ungrouped"', () => {
    expect(matchesStation({}, 'none')).toBe(false);
    expect(matchesStation({}, 3)).toBe(false);
    expect(matchesStation({}, 'all')).toBe(true);
  });
});

describe('stationFilterAvailable', () => {
  it('needs stations and a cloud that returns station_id', () => {
    expect(stationFilterAvailable(2, [{ station_id: null }])).toBe(true);
    expect(stationFilterAvailable(0, [{ station_id: 1 }])).toBe(false);
    expect(stationFilterAvailable(2, [{}, {}])).toBe(false);
    expect(stationFilterAvailable(2, undefined)).toBe(false);
    expect(stationFilterAvailable(2, [])).toBe(false);
  });
});

describe('sectionByStation', () => {
  const d = (name: string, station_id?: number | null) => ({
    name,
    station_id,
  });
  it('orders visible stations, then unknown ids, then ungrouped last', () => {
    const sections = sectionByStation(
      [d('a', null), d('b', 2), d('c', 1), d('d', 77), d('e', 2), d('f', 50)],
      [1, 2],
    );
    expect(sections.map((s) => s.key)).toEqual([1, 2, 50, 77, 'none']);
    expect(sections[1].items.map((i) => i.name)).toEqual(['b', 'e']);
  });
  it('skips empty stations and omits the ungrouped section when empty', () => {
    const sections = sectionByStation([d('b', 2)], [1, 2, 3]);
    expect(sections.map((s) => s.key)).toEqual([2]);
  });
});
