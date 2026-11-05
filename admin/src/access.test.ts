import { describe, expect, it } from 'vitest';
import access from './access';

describe('access', () => {
  it('platform_admin gets both canPlatformAdmin and canConsole', () => {
    const result = access({
      currentUser: {
        userId: 1,
        username: 'u1',
        access: 'platform_admin',
      },
    });

    expect(result.canPlatformAdmin).toBe(true);
    expect(result.canConsole).toBe(true);
  });

  it('customer_admin gets canConsole but not canPlatformAdmin', () => {
    const result = access({
      currentUser: {
        userId: 2,
        username: 'u2',
        access: 'customer_admin',
      },
    });

    expect(result.canPlatformAdmin).toBe(false);
    expect(result.canConsole).toBe(true);
  });

  it('customer_user (staff) gets neither: staff use the mini program, not the web console', () => {
    const result = access({
      currentUser: {
        userId: 3,
        username: 'u3',
        access: 'customer_user',
      },
    });

    expect(result.canPlatformAdmin).toBe(false);
    expect(result.canConsole).toBe(false);
    expect(result.canManageAssets).toBe(false);
  });

  it('returns both false when role is unrecognized', () => {
    const result = access({
      currentUser: {
        userId: 4,
        username: 'u4',
        access: 'wizard' as API.Role,
      },
    });

    expect(result.canPlatformAdmin).toBe(false);
    expect(result.canConsole).toBe(false);
  });

  it('returns both false when currentUser is undefined', () => {
    const result = access({ currentUser: undefined });

    expect(result.canPlatformAdmin).toBeFalsy();
    expect(result.canConsole).toBeFalsy();
  });

  it('returns both false when initialState is undefined', () => {
    const result = access(undefined);

    expect(result.canPlatformAdmin).toBeFalsy();
    expect(result.canConsole).toBeFalsy();
  });
});

describe('canManageAssets', () => {
  const roleOf = (role: string) =>
    access({
      currentUser: { userId: 1, username: 'u', access: role as API.Role },
    }).canManageAssets;

  it('is open to platform_admin and customer_admin only', () => {
    expect(roleOf('platform_admin')).toBe(true);
    expect(roleOf('customer_admin')).toBe(true);
    expect(roleOf('customer_user')).toBe(false);
    expect(roleOf('wizard')).toBe(false);
  });

  it('is falsy without a current user', () => {
    expect(access(undefined).canManageAssets).toBeFalsy();
    expect(access({ currentUser: undefined }).canManageAssets).toBeFalsy();
  });
});
