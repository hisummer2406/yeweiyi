import { beforeEach, describe, expect, it, vi } from 'vitest';

const request = vi.fn();
const forwardAuthFailure = vi.fn(
  (e: any) =>
    e?.response?.status === 401 ||
    (e?.response?.status === 403 && e.response.data?.error === 'not authorized'),
);
vi.mock('@umijs/max', () => ({ request }));
vi.mock('@/requestErrorConfig', () => ({ forwardAuthFailure }));

const { getDeviceTanks, listUserTankGrants, setUserTankGrants } = await import(
  './tankGrants'
);
const { ApiError } = await import('./apiError');

beforeEach(() => vi.clearAllMocks());

describe('tank grants service', () => {
  it('reads the reported tanks of a device (code escaped, leading zeros kept)', async () => {
    request.mockResolvedValue({
      deviceName: '000123',
      reported: true,
      tanks: [{ index: 1 }],
    });
    const res = await getDeviceTanks('000123');
    expect(res.tanks).toEqual([{ index: 1 }]);
    expect(request).toHaveBeenCalledWith('/api/devices/000123/tanks', {
      method: 'GET',
      skipErrorHandler: true,
    });
  });

  it("reads a staff member's grants", async () => {
    request.mockResolvedValue({
      userId: 4,
      grants: [{ deviceName: 'ZCT930001', tanks: [1] }],
    });
    const res = await listUserTankGrants(4);
    expect(res.grants[0]).toEqual({ deviceName: 'ZCT930001', tanks: [1] });
    expect(request.mock.calls[0][0]).toBe('/api/users/4/tank-grants');
  });

  it('PUT replaces the whole set; [] (revoke) is sent as an explicit empty array', async () => {
    request.mockResolvedValue({
      userId: 4,
      deviceName: 'ZCT930001',
      tanks: [],
      changed: true,
    });
    await setUserTankGrants(4, 'ZCT930001', []);
    expect(request).toHaveBeenCalledWith('/api/users/4/tank-grants/ZCT930001', {
      method: 'PUT',
      data: { tanks: [] },
      skipErrorHandler: true,
    });
  });

  it.each([
    ['tanks_unknown', 409],
    ['invalid_tank', 400],
    ['device_customer_mismatch', 409],
    ['grant_not_applicable', 409],
    ['user_not_found', 404],
  ])('surfaces code %s with status %i', async (code, status) => {
    request.mockRejectedValue({ response: { status, data: { code, error: 'x' } } });
    const err = await setUserTankGrants(4, 'D', [1]).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(status);
    expect(err.code).toBe(code);
    expect(err.authHandled).toBe(false);
  });

  it('403 not authorized is an account failure (authHandled), object-level refusals are not', async () => {
    request.mockRejectedValue({
      response: { status: 403, data: { error: 'not authorized' } },
    });
    const err = await listUserTankGrants(4).catch((e) => e);
    expect(err.authHandled).toBe(true);
  });
});
