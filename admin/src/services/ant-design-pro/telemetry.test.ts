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

  it('404 (never reported) resolves to null', async () => {
    request.mockRejectedValue({ response: { status: 404 } });
    await expect(getLatestTelemetry('D1')).resolves.toBeNull();
    expect(forwardAuthFailure).not.toHaveBeenCalled();
  });

  it('other failures are forwarded to the auth handling and rethrown', async () => {
    const err = { response: { status: 401 } };
    request.mockRejectedValue(err);
    await expect(getLatestTelemetry('D1')).rejects.toBe(err);
    expect(forwardAuthFailure).toHaveBeenCalledWith(err);
  });
});
