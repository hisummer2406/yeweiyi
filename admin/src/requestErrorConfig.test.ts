import { beforeEach, describe, expect, it, vi } from 'vitest';

const replace = vi.fn();
const messageError = vi.fn();

vi.mock('@umijs/max', () => ({
  history: { replace },
  getIntl: () => ({
    formatMessage: ({ defaultMessage }: any) => defaultMessage,
  }),
}));
vi.mock('antd', () => ({ message: { error: messageError } }));

const { attachAuth, handleRequestError, handleUnauthorized, errorConfig } =
  await import('./requestErrorConfig');
const { setToken, getToken, clearToken } = await import('@/utils/auth');

describe('attachAuth', () => {
  beforeEach(() => clearToken());

  it('adds Bearer when a token exists', () => {
    setToken('tok');
    expect(attachAuth({ url: '/api/devices' } as any).headers).toMatchObject({
      Authorization: 'Bearer tok',
    });
  });

  it('adds nothing without a token', () => {
    expect(attachAuth({ url: '/api/devices' } as any).headers).toBeUndefined();
  });

  it('skips the login request', () => {
    setToken('tok');
    expect(
      attachAuth({ url: '/api/login', skipAuth: true } as any).headers,
    ).toBeUndefined();
  });
});

describe('handleRequestError', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    clearToken();
    window.history.replaceState({}, '', '/console/devices?x=1');
  });

  it('401 clears the token and redirects to login with the current address', () => {
    setToken('tok');
    handleRequestError({ response: { status: 401, data: {} } }, {});
    expect(getToken()).toBeUndefined();
    expect(replace).toHaveBeenCalledWith(
      `/user/login?redirect=${encodeURIComponent('/console/devices?x=1')}`,
    );
  });

  it('403 "not authorized" (account deleted or disabled) clears the token and redirects', () => {
    setToken('tok');
    handleRequestError(
      { response: { status: 403, data: { error: 'not authorized' } } },
      {},
    );
    expect(getToken()).toBeUndefined();
    expect(replace).toHaveBeenCalled();
    expect(messageError).not.toHaveBeenCalled();
  });

  it('403 "not authorized" + account_unavailable (cloud#79) clears the token and redirects', () => {
    setToken('tok');
    handleRequestError(
      {
        response: {
          status: 403,
          data: { error: 'not authorized', code: 'account_unavailable' },
        },
      },
      {},
    );
    expect(getToken()).toBeUndefined();
    expect(replace).toHaveBeenCalled();
  });

  it('403 "not authorized" + role_not_allowed (cloud#79) keeps the token and says no permission', () => {
    setToken('tok');
    handleRequestError(
      {
        response: {
          status: 403,
          data: { error: 'not authorized', code: 'role_not_allowed' },
        },
      },
      {},
    );
    expect(getToken()).toBe('tok');
    expect(replace).not.toHaveBeenCalled();
    expect(messageError).toHaveBeenCalledWith('没有权限执行此操作。');
  });

  it('other 403s show the backend error and keep the token', () => {
    setToken('tok');
    handleRequestError(
      {
        response: {
          status: 403,
          data: { error: 'not authorized for this device' },
        },
      },
      {},
    );
    expect(messageError).toHaveBeenCalledWith('not authorized for this device');
    expect(getToken()).toBe('tok');
    expect(replace).not.toHaveBeenCalled();
  });

  it('rethrows when skipErrorHandler is set', () => {
    const err = { response: { status: 401, data: {} } };
    expect(() => handleRequestError(err, { skipErrorHandler: true })).toThrow();
    expect(replace).not.toHaveBeenCalled();
  });

  it('skipErrorHandler bypasses the 401 handling, so callers must forward it via handleUnauthorized', () => {
    setToken('tok');
    expect(() =>
      handleRequestError(
        { response: { status: 401, data: {} } },
        { skipErrorHandler: true },
      ),
    ).toThrow();
    expect(getToken()).toBe('tok');

    handleUnauthorized();
    expect(getToken()).toBeUndefined();
    expect(replace).toHaveBeenCalledWith(
      `/user/login?redirect=${encodeURIComponent('/console/devices?x=1')}`,
    );
  });

  it('has no errorThrower, so envelope-less 200 responses are not failures', () => {
    expect((errorConfig.errorConfig as any).errorThrower).toBeUndefined();
  });
});
