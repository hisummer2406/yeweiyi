import { beforeEach, describe, expect, it, vi } from 'vitest';

const request = vi.fn();
const forwardAuthFailure = vi.fn(
  (e: any) =>
    e?.response?.status === 401 ||
    (e?.response?.status === 403 && e.response.data?.error === 'not authorized'),
);

vi.mock('@umijs/max', () => ({ request }));
vi.mock('@/requestErrorConfig', () => ({ forwardAuthFailure }));

const { listAlarms } = await import('./alarms');
const { ApiError } = await import('./apiError');

const query = {
  device: 'ZCT000001',
  beginTime: '2026-10-06T00:00:00.000Z',
  endTime: '2026-10-07T00:00:00.000Z',
  limit: 20,
  offset: 40,
};
const alarm = {
  alarmId: 'a1',
  tank: 1,
  type: 'HIGH_LEVEL',
  severity: 'CRITICAL',
  status: 'ACTIVE',
  text: 'x',
  time: '2026-10-06T08:00:00Z',
};

describe('listAlarms', () => {
  beforeEach(() => vi.clearAllMocks());

  it('asks newest first for one page plus one row (to know if more follow), and skips the global handler', async () => {
    request.mockResolvedValue({ data: [alarm], headers: {} });
    await listAlarms(query);
    expect(request).toHaveBeenCalledWith(
      '/api/alarms',
      expect.objectContaining({
        method: 'GET',
        params: {
          device: 'ZCT000001',
          beginTime: query.beginTime,
          endTime: query.endTime,
          sort: false,
          limit: 21,
          offset: 40,
        },
        getResponse: true,
        skipErrorHandler: true,
      }),
    );
  });

  it('the extra row only says more follow; it is not shown', async () => {
    const rows = Array.from({ length: 21 }, (_, i) => ({ ...alarm, alarmId: `a${i}` }));
    request.mockResolvedValue({ data: rows, headers: {} });
    const res = await listAlarms(query);
    expect(res.rows).toHaveLength(20);
    expect(res.rows[19].alarmId).toBe('a19');
    expect(res.truncated).toBe(true);
    request.mockResolvedValue({ data: rows.slice(0, 19), headers: {} });
    expect((await listAlarms(query)).truncated).toBe(false);
  });

  it('asked for 21 and got 20 (the cloud skipped an unreadable row): there may be more, next stays enabled', async () => {
    const rows = Array.from({ length: 20 }, (_, i) => ({ ...alarm, alarmId: `a${i}` }));
    request.mockResolvedValue({ data: rows, headers: {} });
    const res = await listAlarms(query);
    expect(res.rows).toHaveLength(20);
    expect(res.truncated).toBe(true);
  });

  it('sends the tank filter (#84)', async () => {
    request.mockResolvedValue({ data: [], headers: {} });
    await listAlarms({ ...query, tank: 3 });
    expect(request.mock.calls[0][1].params).toMatchObject({ tank: 3 });
    expect(request.mock.calls[0][1].params.direction).toBeUndefined();
  });

  it('returns the rows and partial=false without the header', async () => {
    request.mockResolvedValue({ data: [alarm], headers: {} });
    await expect(listAlarms(query)).resolves.toEqual({
      rows: [alarm],
      partial: false,
      truncated: false,
    });
  });

  it('reads X-Records-Partial: true as partial', async () => {
    request.mockResolvedValue({
      data: [alarm],
      headers: { 'x-records-partial': 'true' },
    });
    expect((await listAlarms(query)).partial).toBe(true);
  });

  it('an empty window is an empty list, not an error', async () => {
    request.mockResolvedValue({ data: [], headers: {} });
    await expect(listAlarms(query)).resolves.toEqual({
      rows: [],
      partial: false,
      truncated: false,
    });
  });

  it('tolerates a non-array body', async () => {
    request.mockResolvedValue({ data: null, headers: {} });
    expect((await listAlarms(query)).rows).toEqual([]);
  });

  it('500 is thrown as ApiError, not marked handled', async () => {
    request.mockRejectedValue({ response: { status: 500, data: { error: 'boom' } } });
    const err = await listAlarms(query).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(500);
    expect(err.authHandled).toBe(false);
  });

  it('401 is forwarded to the global auth handling', async () => {
    request.mockRejectedValue({ response: { status: 401, data: {} } });
    const err = await listAlarms(query).catch((e) => e);
    expect(forwardAuthFailure).toHaveBeenCalledTimes(1);
    expect(err.authHandled).toBe(true);
  });

  it('a revoked account (403 "not authorized") is marked handled', async () => {
    request.mockRejectedValue({
      response: { status: 403, data: { error: 'not authorized' } },
    });
    expect((await listAlarms(query).catch((e) => e)).authHandled).toBe(true);
  });
});
