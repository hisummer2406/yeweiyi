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

  it('other 403s keep the token and say "no permission", not the backend text', () => {
    setToken('tok');
    handleRequestError(
      {
        response: {
          status: 403,
          data: {
            error: 'not authorized for this device',
            code: 'device_not_authorized',
          },
        },
      },
      {},
    );
    expect(messageError).toHaveBeenCalledWith('没有权限执行此操作。');
    expect(getToken()).toBe('tok');
    expect(replace).not.toHaveBeenCalled();
  });

  it.each([
    [500, 'internal_error', '操作失败，请稍后重试；持续失败请联系平台管理员。'],
    [
      503,
      'device_platform_unavailable',
      '设备数据服务暂时不可用，请稍后重试。',
    ],
    [
      503,
      'command_delivery_unconfirmed',
      '执行结果尚未确认，请先查看操作记录，不要直接重复提交。',
    ],
    [400, 'invalid_request', '提交内容无效，请检查后重试。'],
    [409, 'station_occupied', '数据已变化，请刷新后重试。'],
    [429, 'login_rate_limited', '操作太频繁，请稍后再试。'],
    [418, undefined, '操作失败，请稍后重试；持续失败请联系平台管理员。'],
  ])(
    '%i %s falls back to a localized message, never the backend text',
    (status, code, text) => {
      handleRequestError(
        {
          response: {
            status,
            data: { error: 'pq: relation "users" does not exist', code },
          },
        },
        {},
      );
      expect(messageError).toHaveBeenCalledWith(text);
    },
  );

  it('no response says network or timeout; a request that never went out does not', () => {
    handleRequestError({ request: {} }, {});
    expect(messageError).toHaveBeenLastCalledWith(
      '网络不可用或请求超时，请检查网络后重试。',
    );
    handleRequestError(new Error('bad config'), {});
    expect(messageError).toHaveBeenLastCalledWith(
      '操作失败，请稍后重试；持续失败请联系平台管理员。',
    );
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
