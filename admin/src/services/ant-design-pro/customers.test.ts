import { beforeEach, describe, expect, it, vi } from 'vitest';

const request = vi.fn();
const handleUnauthorized = vi.fn();

vi.mock('@umijs/max', () => ({ request }));
vi.mock('@/requestErrorConfig', () => ({ handleUnauthorized }));

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

  it('401 is forwarded to the global unauthorized handling', async () => {
    request.mockRejectedValue({ response: { status: 401, data: {} } });
    await createCustomer({ name: 'B 油站' }).catch(() => undefined);
    expect(handleUnauthorized).toHaveBeenCalledTimes(1);
  });
});
