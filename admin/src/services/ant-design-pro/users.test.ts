import { beforeEach, describe, expect, it, vi } from 'vitest';

const request = vi.fn();
const forwardAuthFailure = vi.fn(
  (e: any) =>
    e?.response?.status === 401 ||
    (e?.response?.status === 403 && e.response.data?.error === 'not authorized'),
);

vi.mock('@umijs/max', () => ({ request }));
vi.mock('@/requestErrorConfig', () => ({ forwardAuthFailure }));

const { listUsers, createUser, updateUser, moveUser } = await import('./users');
const { ApiError } = await import('./apiError');

describe('users service', () => {
  beforeEach(() => vi.clearAllMocks());

  it('listUsers returns the users of GET /api/users', async () => {
    const users = [{ id: 1, username: 'a', phone: null, role: 'platform_admin' }];
    request.mockResolvedValue({ users });
    await expect(listUsers()).resolves.toEqual(users);
    expect(request).toHaveBeenCalledWith('/api/users', { method: 'GET' });
  });

  it('createUser posts the body and skips the global error handler', async () => {
    request.mockResolvedValue({ id: 2 });
    const body = {
      phone: '13800138000',
      password: 'password1',
      role: 'customer_admin' as const,
      customerId: 1,
    };
    await createUser(body);
    expect(request).toHaveBeenCalledWith(
      '/api/users',
      expect.objectContaining({
        method: 'POST',
        data: body,
        skipErrorHandler: true,
      }),
    );
  });

  it('updateUser puts to /api/users/:id', async () => {
    request.mockResolvedValue({ id: 5 });
    await updateUser(5, { disabled: true });
    expect(request).toHaveBeenCalledWith(
      '/api/users/5',
      expect.objectContaining({
        method: 'PUT',
        data: { disabled: true },
        skipErrorHandler: true,
      }),
    );
  });

  it('moveUser puts the customerId to /api/users/:id/customer', async () => {
    request.mockResolvedValue({ id: 5 });
    await moveUser(5, 9);
    expect(request).toHaveBeenCalledWith(
      '/api/users/5/customer',
      expect.objectContaining({ method: 'PUT', data: { customerId: 9 } }),
    );
  });

  it('409 is thrown as ApiError, not marked handled', async () => {
    request.mockRejectedValue({
      response: { status: 409, data: { error: 'username already exists' } },
    });
    const err = await createUser({
      username: 'u',
      password: 'password1',
      role: 'platform_admin',
    }).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(409);
    expect(err.authHandled).toBe(false);
  });

  it.each([
    ['createUser', () => createUser({ username: 'u', password: 'password1', role: 'platform_admin' })],
    ['updateUser', () => updateUser(1, { disabled: true })],
    ['moveUser', () => moveUser(1, 2)],
  ])('%s forwards a 401 to the global auth handling', async (_n, call) => {
    request.mockRejectedValue({ response: { status: 401, data: {} } });
    const err = await call().catch((e: any) => e);
    expect(forwardAuthFailure).toHaveBeenCalledTimes(1);
    expect(err.authHandled).toBe(true);
  });
});
