import { beforeEach, describe, expect, it, vi } from 'vitest';

const request = vi.fn();
// 与 requestErrorConfig.forwardAuthFailure 同样的判定：401 或「账号已失效」的 403
const forwardAuthFailure = vi.fn(
  (e: any) =>
    e?.response?.status === 401 ||
    (e?.response?.status === 403 && e.response.data?.error === 'not authorized'),
);

vi.mock('@umijs/max', () => ({ request }));
vi.mock('@/requestErrorConfig', () => ({ forwardAuthFailure }));

const { listCustomers, createCustomer } = await import('./customers');
const { ApiError } = await import('./apiError');

describe('listCustomers', () => {
  beforeEach(() => vi.clearAllMocks());

  it('returns the customers of GET /api/customers', async () => {
    const customers = [
      { id: 1, name: 'A 油站', createdAt: '2026-10-07T08:00:00Z' },
    ];
    request.mockResolvedValue({ customers });
    await expect(listCustomers()).resolves.toEqual(customers);
    expect(request).toHaveBeenCalledWith('/api/customers', { method: 'GET' });
  });
});

describe('createCustomer', () => {
  beforeEach(() => vi.clearAllMocks());

  it('posts the name and returns the created customer', async () => {
    const created = { id: 9, name: 'B 油站', createdAt: '2026-10-07T08:00:00Z' };
    request.mockResolvedValue(created);
    await expect(createCustomer({ name: 'B 油站' })).resolves.toEqual(created);
    expect(request).toHaveBeenCalledWith(
      '/api/customers',
      expect.objectContaining({
        method: 'POST',
        data: { name: 'B 油站' },
        skipErrorHandler: true,
      }),
    );
  });

  it('409 name conflict is thrown as ApiError', async () => {
    request.mockRejectedValue({
      response: {
        status: 409,
        data: { error: 'a customer with this name already exists' },
      },
    });
    const err = await createCustomer({ name: 'B 油站' }).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(409);
  });

  it('401 is forwarded to the global auth-failure handling and marked handled', async () => {
    request.mockRejectedValue({ response: { status: 401, data: {} } });
    const err = await createCustomer({ name: 'B 油站' }).catch((e) => e);
    expect(forwardAuthFailure).toHaveBeenCalledTimes(1);
    expect(err.authHandled).toBe(true);
  });

  it('a revoked account (403 "not authorized") is marked handled', async () => {
    request.mockRejectedValue({
      response: { status: 403, data: { error: 'not authorized' } },
    });
    const err = await createCustomer({ name: 'B 油站' }).catch((e) => e);
    expect(err.authHandled).toBe(true);
  });

  it('other errors are not marked handled', async () => {
    request.mockRejectedValue({
      response: { status: 409, data: { error: 'exists' } },
    });
    const err = await createCustomer({ name: 'B 油站' }).catch((e) => e);
    expect(err.authHandled).toBe(false);
  });
});
