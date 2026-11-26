import { describe, expect, it } from 'vitest';
import {
  filterByKeyword,
  matchCustomer,
  matchDevice,
  matchStaff,
  matchStation,
  normalizeKeyword,
} from './listSearch';

describe('normalizeKeyword', () => {
  it('去掉首尾空格并转小写', () => {
    expect(normalizeKeyword('  ZCT01 ')).toBe('zct01');
  });
});

describe('matchCustomer', () => {
  const c = { id: 12, name: '华东石化' };
  it('名称片段匹配', () => {
    expect(matchCustomer(c, '东石')).toBe(true);
  });
  it('客户 ID 只认完整输入', () => {
    expect(matchCustomer(c, '12')).toBe(true);
    expect(matchCustomer(c, '1')).toBe(false);
    expect(matchCustomer(c, '123')).toBe(false);
  });
});

describe('matchStation', () => {
  const s = { name: '一号站', address: '杭州市西湖区', customerName: '华东' };
  it('名称、地址、所属客户名片段匹配', () => {
    expect(matchStation(s, '一号')).toBe(true);
    expect(matchStation(s, '西湖')).toBe(true);
    expect(matchStation(s, '华东')).toBe(true);
    expect(matchStation(s, '上海')).toBe(false);
  });
  it('客户管理员的行没有客户名，不会按客户名命中', () => {
    expect(matchStation({ name: '一号站', address: '' }, '华东')).toBe(false);
  });
});

describe('matchDevice', () => {
  const d = {
    device_name: 'ZCT000123',
    stationName: '一号站',
    customerName: '华东',
  };
  it('设备编号片段按字符串匹配，保留前导零，忽略大小写', () => {
    expect(matchDevice(d, '000123')).toBe(true);
    expect(matchDevice(d, 'zct0001')).toBe(true);
    expect(matchDevice(d, '0124')).toBe(false);
  });
  it('所属油站、所属客户名', () => {
    expect(matchDevice(d, '一号')).toBe(true);
    expect(matchDevice(d, '华东')).toBe(true);
  });
});

describe('matchStaff', () => {
  const u = { phone: '01380013800', customerName: '华东' };
  it('手机号片段（含前导零）、所属客户名', () => {
    expect(matchStaff(u, '0138')).toBe(true);
    expect(matchStaff(u, '13800')).toBe(true);
    expect(matchStaff(u, '华东')).toBe(true);
    expect(matchStaff({ phone: null }, '138')).toBe(false);
  });
});

describe('filterByKeyword', () => {
  const rows = [
    { id: 1, name: 'A站' },
    { id: 2, name: 'B站' },
  ];
  it('空关键词或纯空格返回全部', () => {
    expect(filterByKeyword(rows, '', matchCustomer)).toBe(rows);
    expect(filterByKeyword(rows, '   ', matchCustomer)).toBe(rows);
  });
  it('关键词去空格、忽略大小写后过滤', () => {
    expect(filterByKeyword(rows, ' b ', matchCustomer)).toEqual([rows[1]]);
  });
});
