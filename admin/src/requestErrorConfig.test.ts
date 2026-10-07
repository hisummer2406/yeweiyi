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

const { attachAuth, handleRequestError, errorConfig } = await import(
  './requestErrorConfig'
);
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

  it('403 shows the backend error and keeps the token', () => {
    setToken('tok');
    handleRequestError(
      { response: { status: 403, data: { error: 'not authorized' } } },
      {},
    );
    expect(messageError).toHaveBeenCalledWith('not authorized');
    expect(getToken()).toBe('tok');
    expect(replace).not.toHaveBeenCalled();
  });

  it('rethrows when skipErrorHandler is set', () => {
    const err = { response: { status: 401, data: {} } };
    expect(() => handleRequestError(err, { skipErrorHandler: true })).toThrow();
    expect(replace).not.toHaveBeenCalled();
  });

  it('has no errorThrower, so envelope-less 200 responses are not failures', () => {
    expect((errorConfig.errorConfig as any).errorThrower).toBeUndefined();
  });
});
