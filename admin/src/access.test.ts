import { describe, expect, it } from 'vitest';
import access from './access';

describe('access', () => {
  it('platform_admin gets both canPlatformAdmin and canConsole', () => {
    const result = access({
      currentUser: {
        userid: '1',
        name: 'Admin User',
        access: 'platform_admin',
      },
    });

    expect(result.canPlatformAdmin).toBe(true);
    expect(result.canConsole).toBe(true);
  });

  it('customer_admin gets canConsole but not canPlatformAdmin', () => {
    const result = access({
      currentUser: {
        userid: '2',
        name: 'Customer Admin',
        access: 'customer_admin',
      },
    });

    expect(result.canPlatformAdmin).toBe(false);
    expect(result.canConsole).toBe(true);
  });

  it('customer_user gets canConsole but not canPlatformAdmin', () => {
    const result = access({
      currentUser: {
        userid: '3',
        name: 'Customer User',
        access: 'customer_user',
      },
    });

    expect(result.canPlatformAdmin).toBe(false);
    expect(result.canConsole).toBe(true);
  });

  it('returns both false when role is unrecognized', () => {
    const result = access({
      currentUser: {
        userid: '4',
        name: 'Guest User',
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
