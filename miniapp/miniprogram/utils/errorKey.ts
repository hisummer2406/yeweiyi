// Which i18n key (locales/*.ts, group `err`) words a failed cloud call. Pure (no `wx`), checked in Node.
//
// The user never reads the cloud's own `error` text: it is not in the user's language and it is not
// ours to word. The cloud's machine `code` decides when there is one, the HTTP status otherwise.
// `status` 0 = the request never got an answer (no network, timeout, blocked).
const BY_CODE: Record<string, string> = {
  tank_not_authorized: 'err.tankNotAuthorized',
  device_not_found: 'err.deviceNotFound',
  customer_not_onboarded: 'err.customerNotOnboarded',
  invalid_credentials: 'err.invalidCredentials',
  account_disabled: 'err.accountDisabled',
  session_invalid: 'err.sessionExpired', // 401 for a dead token (yeweiyi-cloud#78)
  account_unavailable: 'err.accountDisabled', // bare 403: account deleted/disabled (yeweiyi-cloud#79)
  role_not_allowed: 'err.forbidden', // bare 403: account fine, role may not (yeweiyi-cloud#79)
};

export function errorKey(status: number, code?: string): string {
  if (status === 0) return 'err.network';
  if (code && BY_CODE[code]) return BY_CODE[code];
  if (status === 401) return 'err.sessionExpired';
  if (status === 403) return 'err.forbidden';
  if (status === 404) return 'err.notFound';
  if (status === 409) return 'err.conflict';
  if (status === 429) return 'err.tooManyRequests';
  if (status >= 500) return 'err.server';
  return 'err.generic';
}
