// Validates a device label's QR payload before it is sent to POST /api/devices/resolve.
//
// The text is the device's public code exactly as printed (possibly a short code such as `000001`,
// possibly a full device name). It is sent as `code` unchanged: nothing here pads, strips zeros, adds
// a prefix or assumes a length — the cloud decides what it resolves to, inside the caller's customer,
// and answers with the actual device name. The cloud accepts [0-9A-Za-z_-], 1–64 characters
// (tank-grants-contract v1.0 §3), so anything else is rejected here before a request is spent on it.
const DEVICE_CODE = /^[0-9A-Za-z_-]{1,64}$/;

export function parseDeviceCodeQr(raw: string): string | null {
  const code = raw.trim();
  return DEVICE_CODE.test(code) ? code : null;
}

// What to tell the user when POST /api/devices/resolve fails, as an i18n key (locales/*.ts, `scanErr`),
// or '' when this failure has no scan-specific wording and the generic error text applies.
// `status` -1 = not an API error.
// 404 device_not_found is deliberately one answer for "no such device / not yours / no granted tank":
// the app must not guess which, and must not offer any way to grant access from here.
export function describeResolveFailure(status: number, code: string): string {
  if (status === 404 && code === 'device_not_found') return 'scanErr.notFound';
  if (status === 410) return 'scanErr.bindRemoved';
  return '';
}
