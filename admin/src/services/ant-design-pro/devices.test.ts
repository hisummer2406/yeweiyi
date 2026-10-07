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

const { listDevices, registerDevice } = await import('./devices');
const { ApiError } = await import('./apiError');

const httpError = (status: number, data: unknown = {}) => ({
  response: { status, data },
});

describe('listDevices', () => {
  beforeEach(() => vi.clearAllMocks());

  it('returns the rows of GET /api/devices', async () => {
    const rows = [
      {
        device_name: 'ZCT000001',
        is_online: true,
        last_online_at: '2026-10-07T08:00:00Z',
        last_offline_at: null,
        updated_at: '2026-10-07T08:00:00Z',
      },
    ];
    request.mockResolvedValue({ rows });
    await expect(listDevices()).resolves.toEqual(rows);
    expect(request).toHaveBeenCalledWith('/api/devices', { method: 'GET' });
  });

  it('keeps the global error handler for list failures', async () => {
    request.mockResolvedValue({ rows: [] });
    await listDevices();
    expect(request.mock.calls[0][1]).not.toHaveProperty('skipErrorHandler');
  });
});

describe('registerDevice', () => {
  beforeEach(() => vi.clearAllMocks());

  it('posts only deviceName and customerId to /api/devices', async () => {
    request.mockResolvedValue({
      data: { deviceName: 'ZCT000001', customerId: 3, created: true },
      status: 201,
    });
    await registerDevice({ deviceName: 'ZCT000001', customerId: 3 });
    expect(request).toHaveBeenCalledTimes(1);
    expect(request).toHaveBeenCalledWith(
      '/api/devices',
      expect.objectContaining({
        method: 'POST',
        data: { deviceName: 'ZCT000001', customerId: 3 },
        getResponse: true,
        skipErrorHandler: true,
      }),
    );
  });

  it('never targets the pairing-secret endpoint', async () => {
    request.mockResolvedValue({
      data: { deviceName: 'ZCT000001', customerId: 3, created: false },
      status: 200,
    });
    await registerDevice({ deviceName: 'ZCT000001', customerId: 3 });
    for (const [url] of request.mock.calls) {
      expect(String(url)).not.toContain('pairing-secret');
    }
  });

  it('201 means created', async () => {
    request.mockResolvedValue({
      data: { deviceName: 'ZCT000001', customerId: 3, created: true },
      status: 201,
    });
    await expect(
      registerDevice({ deviceName: 'ZCT000001', customerId: 3 }),
    ).resolves.toEqual({
      kind: 'created',
      deviceName: 'ZCT000001',
      customerId: 3,
    });
  });

  it('200 means the device already existed for that customer', async () => {
    request.mockResolvedValue({
      data: { deviceName: 'ZCT000001', customerId: 3, created: false },
      status: 200,
    });
    await expect(
      registerDevice({ deviceName: 'ZCT000001', customerId: 3 }),
    ).resolves.toEqual({
      kind: 'exists',
      deviceName: 'ZCT000001',
      customerId: 3,
    });
  });

  it('keeps the leading zeros of two different devices apart', async () => {
    request.mockImplementation(async (_url: string, opts: any) => ({
      data: { ...opts.data, created: true },
      status: 201,
    }));
    const a = await registerDevice({ deviceName: 'ZCT000001', customerId: 3 });
    const b = await registerDevice({ deviceName: 'ZCT000002', customerId: 3 });
    expect(a.deviceName).toBe('ZCT000001');
    expect(b.deviceName).toBe('ZCT000002');
  });

  it.each([
    [409, 'device already belongs to a different customer'],
    [400, 'unknown customerId'],
    [403, 'only a platform administrator may register a device'],
  ])('%i is thrown as ApiError with status and backend error', async (status, error) => {
    request.mockRejectedValue(httpError(status, { error }));
    const err = await registerDevice({
      deviceName: 'ZCT000001',
      customerId: 3,
    }).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(status);
    expect(err.error).toBe(error);
    expect(err.authHandled).toBe(false);
  });

  it('401 is forwarded to the global auth-failure handling and marked handled', async () => {
    request.mockRejectedValue(httpError(401));
    const err = await registerDevice({
      deviceName: 'ZCT000001',
      customerId: 3,
    }).catch((e) => e);
    expect(forwardAuthFailure).toHaveBeenCalledTimes(1);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(401);
    expect(err.authHandled).toBe(true);
  });

  it('a revoked account (403 "not authorized") is forwarded and marked handled', async () => {
    request.mockRejectedValue(httpError(403, { error: 'not authorized' }));
    const err = await registerDevice({
      deviceName: 'ZCT000001',
      customerId: 3,
    }).catch((e) => e);
    expect(err.status).toBe(403);
    expect(err.authHandled).toBe(true);
  });

  it('a network failure has status 0', async () => {
    request.mockRejectedValue(new Error('Network Error'));
    const err = await registerDevice({
      deviceName: 'ZCT000001',
      customerId: 3,
    }).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(0);
  });
});
