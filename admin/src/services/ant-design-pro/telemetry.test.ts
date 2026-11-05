import { beforeEach, describe, expect, it, vi } from 'vitest';

const request = vi.fn();
const forwardAuthFailure = vi.fn();

vi.mock('@umijs/max', () => ({ request }));
vi.mock('@/requestErrorConfig', () => ({ forwardAuthFailure }));

const { getLatestTelemetry } = await import('./telemetry');

describe('getLatestTelemetry', () => {
  beforeEach(() => vi.clearAllMocks());

  it('escapes the device name in the path and skips the global handler', async () => {
    request.mockResolvedValue({ deviceName: 'ZCT/01 #2', tanks: [] });
    await getLatestTelemetry('ZCT/01 #2');
    expect(request).toHaveBeenCalledWith(
      '/api/devices/ZCT%2F01%20%232/telemetry/latest',
      expect.objectContaining({ method: 'GET', skipErrorHandler: true }),
    );
  });

  it.each([
    [{}, { empty: 'never' }], // an older cloud: no code
    [{ code: 'telemetry_not_reported' }, { empty: 'never' }],
    [
      { code: 'telemetry_expired', lastReportedAt: '2026-10-01T00:00:00Z' },
      { empty: 'expired', lastReportedAt: '2026-10-01T00:00:00Z' },
    ],
    [{ code: 'device_not_provisioned' }, { empty: 'notProvisioned' }],
  ])('404 %j is an empty state, told apart by code (yeweiyi-cloud#79)', async (data, want) => {
    request.mockRejectedValue({ response: { status: 404, data } });
    await expect(getLatestTelemetry('D1')).resolves.toEqual(want);
    expect(forwardAuthFailure).not.toHaveBeenCalled();
  });

  it('a 404 with an unknown code is a failure, not "no data"', async () => {
    const err = { response: { status: 404, data: { code: 'something_new' } } };
    request.mockRejectedValue(err);
    await expect(getLatestTelemetry('D1')).rejects.toBe(err);
  });

  it('other failures are forwarded to the auth handling and rethrown', async () => {
    const err = { response: { status: 401 } };
    request.mockRejectedValue(err);
    await expect(getLatestTelemetry('D1')).rejects.toBe(err);
    expect(forwardAuthFailure).toHaveBeenCalledWith(err);
  });
});
