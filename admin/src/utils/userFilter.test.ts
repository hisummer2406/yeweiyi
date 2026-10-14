import { describe, expect, it } from 'vitest';
import {
  filterUsers,
  parseUserFilter,
  serializeUserFilter,
} from './userFilter';

const u = (o: Partial<API.AdminUser>) =>
  ({
    username: null,
    phone: null,
    role: 'customer_user',
    customerId: 1,
    disabled: false,
    ...o,
  }) as API.AdminUser;
const users = [
  u({ id: 1, username: 'Root', role: 'platform_admin', customerId: null }),
  u({ id: 2, phone: '13800138000', role: 'customer_admin', customerId: 5 }),
  u({ id: 3, phone: '13900139000', customerId: 5, disabled: true }),
  u({ id: 4, phone: '13700137000', customerId: 6 }),
];
const none = { keyword: '' };

describe('filterUsers', () => {
  it('matches phone by partial digits, ignoring spaces/dashes', () => {
    expect(filterUsers(users, { keyword: '9000' }).map((x) => x.id)).toEqual([
      3,
    ]);
    expect(
      filterUsers(users, { keyword: '139 0013' }).map((x) => x.id),
    ).toEqual([3]);
  });
  it('matches username case-insensitively', () => {
    expect(filterUsers(users, { keyword: 'ROO' }).map((x) => x.id)).toEqual([
      1,
    ]);
  });
  it('combines customer, role and status', () => {
    expect(filterUsers(users, { ...none, customerId: 5 })).toHaveLength(2);
    expect(
      filterUsers(users, { ...none, customerId: 5, status: 'disabled' }),
    ).toHaveLength(1);
    expect(
      filterUsers(users, { ...none, role: 'customer_admin' }),
    ).toHaveLength(1);
  });
  it('returns everything without filters', () => {
    expect(filterUsers(users, none)).toHaveLength(4);
  });
});

describe('url round trip', () => {
  it('serializes and parses', () => {
    const f = {
      keyword: '138',
      customerId: 5,
      role: 'customer_user' as const,
      status: 'active' as const,
    };
    expect(parseUserFilter(serializeUserFilter(f))).toEqual(f);
    expect(serializeUserFilter(none)).toBe('');
  });
  it('drops invalid values', () => {
    expect(parseUserFilter('?customer=abc&role=x&status=y')).toEqual({
      keyword: '',
      customerId: undefined,
      role: undefined,
      status: undefined,
    });
  });
});
