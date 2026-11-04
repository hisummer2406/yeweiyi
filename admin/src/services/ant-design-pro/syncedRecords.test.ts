import { beforeEach, describe, expect, it, vi } from 'vitest';

const request = vi.fn();
const forwardAuthFailure = vi.fn(
  (e: any) =>
    e?.response?.status === 401 ||
    (e?.response?.status === 403 && e.response.data?.error === 'not authorized'),
);

vi.mock('@umijs/max', () => ({ request }));
vi.mock('@/requestErrorConfig', () => ({ forwardAuthFailure }));

const { listShiftRecords, listInventoryRecords } = await import('./syncedRecords');
const { ApiError } = await import('./apiError');

const query = {
  device: 'ZCT000001',
  beginTime: '2026-11-03T00:00:00.000Z',
  endTime: '2026-11-04T00:00:00.000Z',
  limit: 20,
  offset: 20,
};

describe.each([
  ['/api/shift-records', listShiftRecords],
  ['/api/stock-records', listInventoryRecords],
] as const)('%s', (path, fn) => {
  beforeEach(() => vi.clearAllMocks());

  it('asks newest first with the window, tank and page (limit/offset); skips the global handler', async () => {
    request.mockResolvedValue({ data: { rows: [], syncStatus: 'SYNCED' } });
    await fn({ ...query, tank: 2 });
    expect(request).toHaveBeenCalledWith(
      path,
      expect.objectContaining({
        method: 'GET',
        params: {
          device: 'ZCT000001',
          beginTime: query.beginTime,
          endTime: query.endTime,
          tank: 2,
          sort: false,
          limit: 20,
          offset: 20,
        },
        getResponse: true,
        skipErrorHandler: true,
      }),
    );
  });

  it('returns the envelope as given', async () => {
    request.mockResolvedValue({
      data: {
        rows: [{ tank: 1 }],
        truncated: true,
        partial: true,
        unparsable: 2,
        syncStatus: 'SYNCING',
      },
    });
    expect(await fn(query)).toEqual({
      rows: [{ tank: 1 }],
      truncated: true,
      partial: true,
      unparsable: 2,
      syncStatus: 'SYNCING',
    });
  });

  it('a missing syncStatus stays missing (= cannot tell), never SYNCED', async () => {
    request.mockResolvedValue({ data: {} });
    const res = await fn(query);
    expect(res.syncStatus).toBeUndefined();
    expect(res).toMatchObject({ rows: [], truncated: false, partial: false, unparsable: 0 });
  });

  it('tank_not_authorized is an ApiError with its code, not a logout', async () => {
    request.mockRejectedValue({
      response: {
        status: 403,
        data: { error: 'tank not authorized', code: 'tank_not_authorized' },
      },
    });
    const err = await fn({ ...query, tank: 3 }).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.code).toBe('tank_not_authorized');
    expect(err.authHandled).toBe(false);
  });
});
