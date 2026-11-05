import { describe, expect, it } from 'vitest';
import {
  currentDeviceOf,
  occupantOf,
  occupantsByStation,
} from './stationOccupancy';

const rows = [
  { device_name: '000123', station_id: 3 },
  { device_name: 'ZCT000002', station_id: null },
  { device_name: 'ZCT000003' }, // 旧版云端：没有 station_id 字段
  { device_name: 'ZCT000004', station_id: 3 }, // 历史冲突数据
  { device_name: 'ZCT000005', station_id: 7 },
];

describe('occupantsByStation', () => {
  it('maps station ids to device codes, keeping leading zeros and order', () => {
    const m = occupantsByStation(rows);
    expect(m.get(3)).toEqual(['000123', 'ZCT000004']);
    expect(m.get(7)).toEqual(['ZCT000005']);
  });

  it('ignores ungrouped rows and rows without a station_id field', () => {
    const m = occupantsByStation(rows);
    expect([...m.keys()].sort()).toEqual([3, 7]);
  });
});

describe('occupantOf', () => {
  it('returns the device holding the station', () => {
    expect(occupantOf(rows, 7)).toBe('ZCT000005');
  });

  it('does not count the device itself as an occupant', () => {
    expect(occupantOf(rows, 7, 'ZCT000005')).toBeUndefined();
  });

  it('still reports another device when legacy data has two on one station', () => {
    expect(occupantOf(rows, 3, '000123')).toBe('ZCT000004');
  });

  it('returns undefined for a free station', () => {
    expect(occupantOf(rows, 99)).toBeUndefined();
  });
});

describe('currentDeviceOf', () => {
  it('one device -> its code (leading zeros kept)', () => {
    expect(currentDeviceOf(rows, 7)).toBe('ZCT000005');
  });
  it('none -> null', () => {
    expect(currentDeviceOf(rows, 99)).toBeNull();
  });
  it('several (legacy conflict) -> undefined, never picks one', () => {
    expect(currentDeviceOf(rows, 3)).toBeUndefined();
  });
});
