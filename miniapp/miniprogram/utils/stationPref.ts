import { userKeyFromToken } from './auth';
import { get } from './request';
import { getToken } from './storage';
import { recordsContext, type RecordsContext } from './stations';
import type { Device, Station } from '../types/api';

// The station the user last picked on the home page, per account: keyed by the account's user key
// (`userKeyFromToken`, "u12") so switching accounts on one phone does not restore somebody else's
// station. An empty key (no usable token) stores and restores nothing. Reads and writes are best
// effort — storage can be unavailable (private mode, cleared data) and the page must still work.
const prefKey = (userKey: string) => `station:${userKey}`;

export function getRememberedStation(userKey: string): string | undefined {
  if (!userKey) return undefined;
  try {
    const v = wx.getStorageSync<string>(prefKey(userKey));
    return v || undefined;
  } catch {
    return undefined;
  }
}

export function rememberStation(userKey: string, key: string): void {
  if (!userKey) return;
  try {
    wx.setStorageSync(prefKey(userKey), key);
  } catch {
    // not remembered; next launch falls back to the default
  }
}

// The 我的记录 pages' station and device (miniapp#16): the home page's current station, decided by the
// same rules (utils/stations.ts recordsContext) from a fresh GET /api/devices + /api/stations. Stations
// are an enhancement on the home page, but here they are the whole context: an older cloud (404) or a
// failed /api/stations reads as "no station", never as a guessed device. A failed /api/devices rejects.
export function loadRecordsContext(pinned?: string): Promise<RecordsContext> {
  return Promise.all([
    get<{ rows: Device[] }>('/api/devices'),
    get<{ rows: Station[] }>('/api/stations').catch(() => ({ rows: [] as Station[] })),
  ]).then(([devRes, stRes]) =>
    recordsContext(devRes.rows, stRes.rows, getRememberedStation(userKeyFromToken(getToken())), pinned),
  );
}
