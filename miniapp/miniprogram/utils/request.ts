import { BASE_URL } from '../config/env';
import { clearToken, getToken } from './storage';

interface RequestOptions {
  url: string;
  method?: 'GET' | 'POST';
  data?: Record<string, unknown>;
  needAuth?: boolean;
}

// Carries the HTTP status and the full parsed body alongside the message. Most callers only need
// `message` (yeweiyi-cloud's handlers put a human string in `error`), but a couple of endpoints
// return a structured body even on a non-2xx status specifically so a caller can do something
// smarter than show an error toast — GET /api/oil-in-out's 502 still carries `rows` (whatever *did*
// parse) plus `unparsable`/`legacySkipped` counts, and collapsing that to just the error string
// would throw away the whole point of that response shape. See device-detail.ts's loadOilInOut.
export class ApiError extends Error {
  status: number;
  body: unknown;

  constructor(message: string, status: number, body?: unknown) {
    super(message);
    this.status = status;
    this.body = body;
  }
}

// Every route except /api/login needs Authorization: Bearer {jwt} (see yeweiyi-cloud CLAUDE.md
// "API surface"). A 403 means "not your device" (bound-or-not is indistinguishable by design, and
// an unbound device answers the same 403 as one that does not exist at all) and must NOT log the
// user out; only a 401 means the token itself is bad/expired.
export function request<T>(options: RequestOptions): Promise<T> {
  const { url, method = 'GET', data, needAuth = true } = options;
  const header: Record<string, string> = { 'content-type': 'application/json' };
  if (needAuth) {
    const token = getToken();
    if (token) {
      header.Authorization = `Bearer ${token}`;
    }
  }

  return new Promise<T>((resolve, reject) => {
    wx.request({
      url: `${BASE_URL}${url}`,
      method,
      data,
      header,
      success: (res) => {
        const status = res.statusCode;
        if (status >= 200 && status < 300) {
          resolve(res.data as T);
          return;
        }
        // Not for /api/login: there a 401 is just "wrong username or password", and bouncing to the
        // login page would reload it and wipe what the user typed instead of showing the message.
        if (status === 401 && needAuth) {
          clearToken();
          wx.reLaunch({ url: '/pages/login/login' });
        }
        const body = res.data as { error?: string } | undefined;
        reject(new ApiError(body?.error || `请求失败 (${status})`, status, res.data));
      },
      fail: (err) => {
        reject(new ApiError(err.errMsg || '网络请求失败', 0));
      },
    });
  });
}

export function get<T>(url: string, data?: Record<string, unknown>, needAuth = true): Promise<T> {
  return request<T>({ url, method: 'GET', data, needAuth });
}

export function post<T>(url: string, data?: Record<string, unknown>, needAuth = true): Promise<T> {
  return request<T>({ url, method: 'POST', data, needAuth });
}
