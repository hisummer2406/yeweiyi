import { beforeEach, describe, expect, it, vi } from 'vitest';

const request = vi.fn();
const forwardAuthFailure = vi.fn(
  (e: any) =>
    e?.response?.status === 401 ||
    (e?.response?.status === 403 && e.response.data?.error === 'not authorized'),
);

vi.mock('@umijs/max', () => ({ request }));
vi.mock('@/requestErrorConfig', () => ({ forwardAuthFailure }));

const {
  listStations,
  createStation,
  updateStation,
  deleteStation,
  assignDeviceStation,
  replaceStationDevice,
} = await import('./stations');
const { ApiError } = await import('./apiError');

const station = {
  id: 3,
  customerId: 1,
  name: '一号站',
  address: '某路 1 号',
  createdAt: '2026-10-07T08:00:00Z',
};

beforeEach(() => vi.clearAllMocks());

describe('listStations', () => {
  it('returns the rows of GET /api/stations', async () => {
    request.mockResolvedValue({ rows: [station] });
    await expect(listStations()).resolves.toEqual([station]);
    expect(request).toHaveBeenCalledWith('/api/stations', { method: 'GET' });
  });

  it('silent passes skipErrorHandler and returns the rows', async () => {
    request.mockResolvedValue({ rows: [station] });
    await expect(listStations({ silent: true })).resolves.toEqual([station]);
    expect(request).toHaveBeenCalledWith('/api/stations', {
      method: 'GET',
      skipErrorHandler: true,
    });
  });

  it('silent turns a 404 into an ApiError with status 404', async () => {
    request.mockRejectedValue({ response: { status: 404, data: {} } });
    const err = await listStations({ silent: true }).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(404);
    expect(err.authHandled).toBe(false);
  });

  it('silent marks a 401 as authHandled', async () => {
    request.mockRejectedValue({ response: { status: 401, data: {} } });
    const err = await listStations({ silent: true }).catch((e) => e);
    expect(err.status).toBe(401);
    expect(err.authHandled).toBe(true);
  });

  it('non-silent does not skip the global error handler', async () => {
    request.mockResolvedValue({ rows: [] });
    await listStations({ silent: false });
    expect(request).toHaveBeenCalledWith('/api/stations', { method: 'GET' });
  });
});

describe('createStation', () => {
  it('posts the body and returns the created station', async () => {
    request.mockResolvedValue(station);
    await expect(
      createStation({ customerId: 1, name: '一号站', address: '某路 1 号' }),
    ).resolves.toEqual(station);
    expect(request).toHaveBeenCalledWith(
      '/api/stations',
      expect.objectContaining({
        method: 'POST',
        data: { customerId: 1, name: '一号站', address: '某路 1 号' },
        skipErrorHandler: true,
      }),
    );
  });

  it('throws ApiError with the status on 409', async () => {
    request.mockRejectedValue({
      response: { status: 409, data: { error: 'dup' } },
    });
    const err = await createStation({ name: 'x' }).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(409);
    expect(err.authHandled).toBe(false);
  });

  it('marks an account-invalid 403 as handled', async () => {
    request.mockRejectedValue({
      response: { status: 403, data: { error: 'not authorized' } },
    });
    const err = await createStation({ name: 'x' }).catch((e) => e);
    expect(err.authHandled).toBe(true);
  });
});

describe('updateStation / deleteStation', () => {
  it('PUTs name and address to /api/stations/:id', async () => {
    request.mockResolvedValue(station);
    await updateStation(3, { name: '新名', address: '' });
    expect(request).toHaveBeenCalledWith(
      '/api/stations/3',
      expect.objectContaining({
        method: 'PUT',
        data: { name: '新名', address: '' },
        skipErrorHandler: true,
      }),
    );
  });

  it('DELETEs /api/stations/:id and resolves on 204', async () => {
    request.mockResolvedValue(undefined);
    await expect(deleteStation(3)).resolves.toBeUndefined();
    expect(request).toHaveBeenCalledWith(
      '/api/stations/3',
      expect.objectContaining({ method: 'DELETE', skipErrorHandler: true }),
    );
  });

  it('delete 409 (station still has devices) is an ApiError', async () => {
    request.mockRejectedValue({ response: { status: 409, data: {} } });
    const err = await deleteStation(3).catch((e) => e);
    expect(err.status).toBe(409);
  });
});

describe('assignDeviceStation', () => {
  it('escapes the device name in the path and sends the station id', async () => {
    request.mockResolvedValue({ deviceName: 'A/B', stationId: 3, changed: true });
    await assignDeviceStation('A/B', 3);
    expect(request).toHaveBeenCalledWith(
      '/api/devices/A%2FB/station',
      expect.objectContaining({
        method: 'PUT',
        data: { stationId: 3 },
        skipErrorHandler: true,
      }),
    );
  });

  it('sends an explicit null to clear, never omits the key', async () => {
    request.mockResolvedValue({ deviceName: 'D1', stationId: null, changed: true });
    await assignDeviceStation('D1', null);
    const [, options] = request.mock.calls[0];
    expect(options.data).toStrictEqual({ stationId: null });
    expect('stationId' in options.data).toBe(true);
  });
});

describe('replaceStationDevice', () => {
  it('POSTs the contract body to the station-scoped endpoint, codes kept as strings', async () => {
    request.mockResolvedValue({
      stationId: 3,
      deviceName: 'ZCT000999',
      previousDeviceName: '000123',
      changed: true,
    });
    const res = await replaceStationDevice(3, {
      deviceName: 'ZCT000999',
      expectedCurrentDeviceName: '000123',
    });
    expect(res.changed).toBe(true);
    expect(request).toHaveBeenCalledTimes(1);
    expect(request).toHaveBeenCalledWith('/api/stations/3/replace-device', {
      method: 'POST',
      data: { deviceName: 'ZCT000999', expectedCurrentDeviceName: '000123' },
      skipErrorHandler: true,
    });
  });

  it('sends an explicit null expectedCurrentDeviceName for an empty station (key must exist)', async () => {
    request.mockResolvedValue({ changed: true });
    await replaceStationDevice(3, {
      deviceName: 'B',
      expectedCurrentDeviceName: null,
    });
    expect(request.mock.calls[0][1].data).toEqual({
      deviceName: 'B',
      expectedCurrentDeviceName: null,
    });
  });

  it('surfaces the machine code and currentDeviceName of a stale-page 409', async () => {
    request.mockRejectedValue({
      response: {
        status: 409,
        data: {
          code: 'station_device_changed',
          error: 'x',
          currentDeviceName: 'ZCT000777',
        },
      },
    });
    const err = await replaceStationDevice(3, {
      deviceName: 'B',
      expectedCurrentDeviceName: 'A',
    }).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(409);
    expect(err.code).toBe('station_device_changed');
    expect(err.currentDeviceName).toBe('ZCT000777');
    expect(err.authHandled).toBe(false);
  });

  it('a 403 not authorized is an account failure, not an object-level refusal', async () => {
    request.mockRejectedValue({
      response: { status: 403, data: { error: 'not authorized' } },
    });
    const err = await replaceStationDevice(3, {
      deviceName: 'B',
      expectedCurrentDeviceName: 'A',
    }).catch((e) => e);
    expect(err.authHandled).toBe(true);
  });
});

describe('assignDeviceStation conflict details', () => {
  it('carries code station_occupied and occupiedBy', async () => {
    request.mockRejectedValue({
      response: {
        status: 409,
        data: { code: 'station_occupied', error: 'x', occupiedBy: '000777' },
      },
    });
    const err = await assignDeviceStation('D1', 3).catch((e) => e);
    expect(err.code).toBe('station_occupied');
    expect(err.occupiedBy).toBe('000777');
  });
});
