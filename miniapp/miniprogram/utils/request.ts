import { BASE_URL } from '../config/env';
import { sessionDecision } from './loginFlow';
import { loginUrlForSession } from './sceneEntry';
import { clearToken, getToken, setToken } from './storage';

interface RequestOptions {
  url: string;
  method?: 'GET' | 'POST';
  data?: Record<string, unknown>;
  needAuth?: boolean;
  // POST /api/devices/bind answers 401 for "device not found / not yours" while the token is fine, so
  // that call must not end the session on 401. (Its 403 "not authorized" still does.)
  keepSessionOn401?: boolean;
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
// "API surface"). Two statuses mean the session is over:
//   - 401: the token itself is bad/expired.
//   - 403 with the bare body "not authorized": yeweiyi-cloud's authMiddleware, which re-reads the
//     user row on every request, found the user deleted or disabled — the token is still
//     well-formed and unexpired, so this is the only signal. (A DB reset hits the same path.)
// Any other 403 means "not your device" (bound-or-not is indistinguishable by design, and an
// unbound device answers the same 403 as one that does not exist at all; its body is
// "not authorized for this device") and must NOT log the user out.
// Sliding renewal (yeweiyi-cloud#51): once a token is past half its life, a successful authorized
// response carries a replacement in X-Renewed-Token. Overwriting the stored token is all the client
// does; the cloud caps the whole chain at authAt + auth.maxSessionAge, after which the next 401
// sends the user back to login as usual. Header names are matched case-insensitively because
// wx.request hands back whatever casing the transport used (HTTP/2 lowercases).
function adoptRenewedToken(header: Record<string, string> | undefined) {
  if (!header) return;
  for (const name of Object.keys(header)) {
    if (name.toLowerCase() === 'x-renewed-token' && header[name]) {
      setToken(header[name]);
      return;
    }
  }
}

let loggingOut = false;
// The reason the session was last ended, kept until a request goes out with a token again. Requests
// already queued when the token is cleared go out bare and answer 401; without this they would
// reLaunch a plain login page over the one that carries the notice.
let endedNotice = '';

function endSession(notice?: string) {
  if (notice) endedNotice = notice;
  // A page fires several requests in parallel (the home page does one per device); without this
  // each failure would reLaunch the login page again.
  if (loggingOut) return;
  loggingOut = true;
  clearToken();
  wx.reLaunch({
    url: loginUrlForSession(endedNotice || undefined),
    complete: () => {
      loggingOut = false;
    },
  });
}

export function request<T>(options: RequestOptions): Promise<T> {
  const { url, method = 'GET', data, needAuth = true, keepSessionOn401 = false } = options;
  const header: Record<string, string> = { 'content-type': 'application/json' };
  // The token this request goes out with; an answer is only allowed to end the session if it is still
  // the stored one when it arrives (see sessionDecision in loginFlow.ts).
  let sentToken = '';
  if (needAuth) {
    const token = getToken();
    if (token) {
      sentToken = token;
      endedNotice = '';
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
          if (needAuth) adoptRenewedToken(res.header as Record<string, string>);
          resolve(res.data as T);
          return;
        }
        // Not for the login calls (/api/auth/wechat/*, needAuth=false): there is no session to end.
        // A 401 there is "code spent / not bound" and a 403 is "disabled" — bouncing to the login page
        // would reload it and replace the message with a retry loop, so they are shown instead.
        const body = res.data as { error?: string } | undefined;
        // 403 customer_not_onboarded (customer-onboarding-contract §3): the customer is still being
        // configured. Not a dead account, and it carries no X-Renewed-Token. The token is dropped
        // because a login page that still finds one would walk straight back in; the login page shows
        // the notice and does not retry by itself. Whether this answer may end the session at all is
        // decided by sessionDecision (loginFlow.ts) (an answer for an already-replaced session may not).
        const decision = sessionDecision({
          status,
          body: res.data,
          needAuth,
          keepSessionOn401,
          sentToken,
          currentToken: getToken(),
        });
        if (decision.end) endSession(decision.notice);
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

export function post<T>(
  url: string,
  data?: Record<string, unknown>,
  needAuth = true,
  extra?: { keepSessionOn401?: boolean },
): Promise<T> {
  return request<T>({ url, method: 'POST', data, needAuth, ...extra });
}
