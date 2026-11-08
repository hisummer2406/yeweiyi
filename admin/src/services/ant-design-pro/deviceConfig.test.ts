import { beforeEach, describe, expect, it, vi } from 'vitest';

const request = vi.fn();
const forwardAuthFailure = vi.fn((e: any) => e?.response?.status === 401);
vi.mock('@umijs/max', () => ({ request }));
vi.mock('@/requestErrorConfig', () => ({ forwardAuthFailure }));

const svc = await import('./deviceConfig');
const { ApiError } = await import('./apiError');

const httpError = (status: number, data: unknown = {}) => ({ response: { status, data } });

beforeEach(() => vi.clearAllMocks());

describe('deviceConfig services', () => {
  it('encodes the device and skips the global error handler', async () => {
    request.mockResolvedValue({ deviceName: 'A/B', tanks: [{ tank: 1 }] });
    await expect(svc.getTankSettings('A/B')).resolves.toEqual([{ tank: 1 }]);
    expect(request).toHaveBeenCalledWith('/api/devices/A%2FB/tank-settings', {
      method: 'GET',
      skipErrorHandler: true,
    });
  });

  it('reads "never reported" as null, not as an error', async () => {
    request.mockRejectedValue(httpError(404, { code: 'device_settings_not_reported' }));
    await expect(svc.getDeviceSettings('D1')).resolves.toBeNull();
    request.mockRejectedValue(httpError(404, { code: 'calibration_not_reported' }));
    await expect(svc.getTankCalibration('D1', 2)).resolves.toBeNull();
  });

  it('tells a replay (200) from a new acceptance (202)', async () => {
    const cmd = { commandId: 'c1', status: 'received', terminal: false };
    request.mockResolvedValue({ data: cmd, status: 200 });
    const body = {
      method: 'config.setTankSettings' as const,
      args: { tank: 1, alarmHighLevelMm: 610 },
      reason: 'r',
      requestId: 'adm-1',
      expectedStationId: null,
    };
    await expect(svc.issueConfigCommand('D1', body)).resolves.toEqual({ command: cmd, replayed: true });
    expect(request.mock.calls[0][1].data).toEqual(body);
    expect(request.mock.calls[0][1].data).toHaveProperty('expectedStationId', null);
    request.mockResolvedValue({ data: cmd, status: 202 });
    await expect(svc.issueConfigCommand('D1', body)).resolves.toEqual({ command: cmd, replayed: false });
  });

  it('keeps conflict details on the ApiError', async () => {
    request.mockRejectedValue(httpError(409, { code: 'config_commands_disabled', scope: 'device' }));
    const err = await svc
      .issueConfigCommand('D1', {
        method: 'config.setTankSettings',
        args: { tank: 1 },
        reason: 'r',
        requestId: 'x',
        expectedStationId: 1,
      })
      .catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.code).toBe('config_commands_disabled');
    expect(err.data?.scope).toBe('device');
  });

  it('finds a submission by requestId (list shape, 0 or 1)', async () => {
    request.mockResolvedValue({ commands: [] });
    await expect(svc.findCommandByRequestId('D1', 'adm-1')).resolves.toBeUndefined();
    expect(request.mock.calls[0][1].params).toEqual({ requestId: 'adm-1' });
  });
});
