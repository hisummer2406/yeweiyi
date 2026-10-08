// Validates a device label's QR payload.
//
// The label encodes only the device's short code (e.g. `000001`) — no pairing secret
// (yeweiyi-cloud#56 made `pairingToken` optional on POST /api/devices/bind; issue #2 here). The raw
// text is sent as `deviceName` and the cloud resolves it to the full name (`ZCT000001`) within the
// caller's own customer. The cloud accepts letters and digits only, at most 32 characters, so
// anything else is rejected here before a request is spent on it.
const DEVICE_CODE = /^[A-Za-z0-9]{1,32}$/;

export function parseDeviceCodeQr(raw: string): string | null {
  const code = raw.trim();
  return DEVICE_CODE.test(code) ? code : null;
}
