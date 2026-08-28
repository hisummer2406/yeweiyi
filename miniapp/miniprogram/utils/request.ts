import { BASE_URL } from '../config/env';
import { clearToken, getToken } from './storage';

interface RequestOptions {
  url: string;
  method?: 'GET' | 'POST';
  data?: Record<string, unknown>;
  needAuth?: boolean;
}

// Carries the HTTP status alongside the message so callers can special-case e.g. 404 ("nothing
// polled yet" on /api/oilcan/latest) without re-parsing a plain Error's message string.
export class ApiError extends Error {
  status: number;

  constructor(message: string, status: number) {
    super(message);
    this.status = status;
  }
}

// Every route except /api/login needs Authorization: Bearer {jwt} (see CLAUDE.md "Sibling repos").
// A 403 means "not your device" (bound-or-not is indistinguishable by design) and must NOT log the
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
        if (status === 401) {
          clearToken();
          wx.reLaunch({ url: '/pages/login/login' });
        }
        const body = res.data as { error?: string } | undefined;
        reject(new ApiError(body?.error || `请求失败 (${status})`, status));
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
