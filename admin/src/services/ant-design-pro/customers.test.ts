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

const {
  listCustomers,
  createCustomer,
  renameCustomer,
  deleteCustomer,
  getCustomerOnboarding,
  completeCustomerOnboarding,
} = await import('./customers');
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

describe('renameCustomer', () => {
  beforeEach(() => vi.clearAllMocks());

  it('puts the new name to /api/customers/:id', async () => {
    const renamed = { id: 5, name: 'C 油站', createdAt: '' };
    request.mockResolvedValue(renamed);
    await expect(renameCustomer(5, 'C 油站')).resolves.toEqual(renamed);
    expect(request).toHaveBeenCalledWith(
      '/api/customers/5',
      expect.objectContaining({
        method: 'PUT',
        data: { name: 'C 油站' },
        skipErrorHandler: true,
      }),
    );
  });

  it('409 is thrown as ApiError and not marked handled', async () => {
    request.mockRejectedValue({ response: { status: 409, data: { error: 'dup' } } });
    const err = await renameCustomer(5, 'x').catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(409);
    expect(err.authHandled).toBe(false);
  });

  it('401 is forwarded to the global auth handling', async () => {
    request.mockRejectedValue({ response: { status: 401, data: {} } });
    const err = await renameCustomer(5, 'x').catch((e) => e);
    expect(forwardAuthFailure).toHaveBeenCalledTimes(1);
    expect(err.authHandled).toBe(true);
  });
});

describe('deleteCustomer', () => {
  beforeEach(() => vi.clearAllMocks());

  it('sends DELETE /api/customers/:id and resolves on 204', async () => {
    request.mockResolvedValue(undefined);
    await expect(deleteCustomer(7)).resolves.toBeUndefined();
    expect(request).toHaveBeenCalledWith(
      '/api/customers/7',
      expect.objectContaining({ method: 'DELETE', skipErrorHandler: true }),
    );
  });

  it('409 (still in use) is thrown as ApiError', async () => {
    request.mockRejectedValue({ response: { status: 409, data: { error: 'in use' } } });
    const err = await deleteCustomer(7).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(409);
  });

  it('a revoked account (403 "not authorized") is marked handled', async () => {
    request.mockRejectedValue({ response: { status: 403, data: { error: 'not authorized' } } });
    const err = await deleteCustomer(7).catch((e) => e);
    expect(err.authHandled).toBe(true);
  });
});

describe('customer onboarding', () => {
  beforeEach(() => vi.clearAllMocks());

  const progress = {
    customerId: 7,
    status: 'configuring',
    complete: false,
    missing: ['customer_admin', 'station', 'device_in_station'],
    counts: { customerAdmins: 0, stations: 0, devicesInStation: 0 },
  };

  it('reads progress from GET /api/customers/:id/onboarding', async () => {
    request.mockResolvedValue(progress);
    await expect(getCustomerOnboarding(7)).resolves.toEqual(progress);
    expect(request).toHaveBeenCalledWith(
      '/api/customers/7/onboarding',
      expect.objectContaining({ method: 'GET', skipErrorHandler: true }),
    );
  });

  it('completes with a bodyless POST', async () => {
    request.mockResolvedValue({ ...progress, status: 'active', complete: true });
    await completeCustomerOnboarding(7);
    const [url, opts] = request.mock.calls[0];
    expect(url).toBe('/api/customers/7/onboarding/complete');
    expect(opts).toMatchObject({ method: 'POST', skipErrorHandler: true });
    expect(opts.data).toBeUndefined();
  });

  it('409 onboarding_incomplete carries the machine code', async () => {
    request.mockRejectedValue({
      response: {
        status: 409,
        data: { code: 'onboarding_incomplete', missing: ['station'] },
      },
    });
    const err = await completeCustomerOnboarding(7).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(409);
    expect(err.code).toBe('onboarding_incomplete');
    expect(err.authHandled).toBe(false);
  });
});
