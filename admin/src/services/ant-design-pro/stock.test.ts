import { beforeEach, describe, expect, it, vi } from 'vitest';

const request = vi.fn();
const forwardAuthFailure = vi.fn(
  (e: any) =>
    e?.response?.status === 401 ||
    (e?.response?.status === 403 && e.response.data?.error === 'not authorized'),
);

vi.mock('@umijs/max', () => ({ request }));
vi.mock('@/requestErrorConfig', () => ({ forwardAuthFailure }));

const { listStockEvents, STOCK_EVENT_FORMAT_UNSUPPORTED } = await import('./stock');
const { ApiError } = await import('./apiError');

const query = {
  device: 'ZCT000001',
  beginTime: '2026-10-06T00:00:00.000Z',
  endTime: '2026-10-07T00:00:00.000Z',
  limit: 20,
  offset: 0,
};
const row = { eventId: 'e1', tank: 1, direction: 'IN', source: 'AUTO' };

describe('listStockEvents', () => {
  beforeEach(() => vi.clearAllMocks());

  it('asks newest first for one page (limit/offset), and skips the global handler', async () => {
    request.mockResolvedValue({ data: { rows: [] } });
    await listStockEvents(query);
    expect(request).toHaveBeenCalledWith(
      '/api/oil-in-out',
      expect.objectContaining({
        method: 'GET',
        params: {
          device: 'ZCT000001',
          beginTime: query.beginTime,
          endTime: query.endTime,
          sort: false,
          limit: 20,
          offset: 0,
        },
        getResponse: true,
        skipErrorHandler: true,
      }),
    );
  });

  it('sends the tank and direction filters (#84)', async () => {
    request.mockResolvedValue({ data: { rows: [] } });
    await listStockEvents({ ...query, tank: 2, direction: 'OUT' });
    expect(request.mock.calls[0][1].params).toMatchObject({ tank: 2, direction: 'OUT' });
  });

  it('returns rows and every counter of a 200', async () => {
    request.mockResolvedValue({
      data: {
        rows: [row],
        parsed: 1,
        unparsable: 0,
        legacySkipped: 4,
        invalidSource: 2,
        folded: 3,
        truncated: true,
        partial: true,
      },
    });
    const res = await listStockEvents(query);
    expect(res).toEqual({
      rows: [row],
      parsed: 1,
      unparsable: 0,
      legacySkipped: 4,
      invalidSource: 2,
      folded: 3,
      truncated: true,
      partial: true,
    });
    expect(res.degraded).toBeUndefined();
  });

  it('missing counters default to zero/false', async () => {
    request.mockResolvedValue({ data: {} });
    expect(await listStockEvents(query)).toEqual({
      rows: [],
      parsed: 0,
      unparsable: 0,
      legacySkipped: 0,
      invalidSource: 0,
      folded: 0,
      truncated: false,
      partial: false,
    });
  });

  it('502 with the format-unsupported code is a degraded result, not a failure', async () => {
    request.mockRejectedValue({
      response: {
        status: 502,
        data: {
          rows: [row],
          parsed: 1,
          unparsable: 3,
          code: STOCK_EVENT_FORMAT_UNSUPPORTED,
          error: 'device firmware not upgraded',
          device: 'ZCT000001',
          sample: 'DetailInoutRecord{...}',
        },
      },
    });
    const res = await listStockEvents(query);
    expect(res.rows).toEqual([row]);
    expect(res.unparsable).toBe(3);
    expect(res.degraded).toEqual({
      code: STOCK_EVENT_FORMAT_UNSUPPORTED,
      error: 'device firmware not upgraded',
      device: 'ZCT000001',
      sample: 'DetailInoutRecord{...}',
    });
    expect(forwardAuthFailure).not.toHaveBeenCalled();
  });

  it('502 with empty rows is still a degraded result', async () => {
    request.mockRejectedValue({
      response: {
        status: 502,
        data: { rows: [], unparsable: 5, code: STOCK_EVENT_FORMAT_UNSUPPORTED },
      },
    });
    const res = await listStockEvents(query);
    expect(res.rows).toEqual([]);
    expect(res.degraded?.code).toBe(STOCK_EVENT_FORMAT_UNSUPPORTED);
  });

  it('a 502 without that code is a normal failure', async () => {
    request.mockRejectedValue({
      response: { status: 502, data: { error: 'bad gateway' } },
    });
    const err = await listStockEvents(query).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(502);
  });

  it('500 is thrown as ApiError, not marked handled', async () => {
    request.mockRejectedValue({ response: { status: 500, data: { error: 'boom' } } });
    const err = await listStockEvents(query).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.authHandled).toBe(false);
  });

  it('401 is forwarded to the global auth handling', async () => {
    request.mockRejectedValue({ response: { status: 401, data: {} } });
    const err = await listStockEvents(query).catch((e) => e);
    expect(forwardAuthFailure).toHaveBeenCalledTimes(1);
    expect(err.authHandled).toBe(true);
  });
});
