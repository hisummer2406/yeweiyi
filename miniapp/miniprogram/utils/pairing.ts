// Parses a device-pairing QR code's payload.
//
// **The QR code's content format is not designed cloud-side** — yeweiyi-cloud's own CLAUDE.md
// lists it under "Do not assume these are decided": binding needs `deviceName` and a pairing
// secret minted via `POST /api/devices/:device/pairing-secret` (platform_admin only, returned in
// plaintext exactly once), but "how they are encoded on the physical label ... and who calls the
// minting endpoint at install time belong to whoever designs that label."
//
// This repo is the one place that has to pick *something* in order to have a working scan flow, so
// it picks a JSON object: no delimiter-escaping questions (a pairing secret is base64url and could
// collide with a chosen separator), and it extends cleanly if a future field is needed. **Treat
// this as a placeholder, not a frozen decision** — if the physical label ends up encoding something
// else (a deeplink, a bare separated string), this is the only place that needs to change.
export interface DevicePairing {
  deviceName: string;
  pairingToken: string;
}

export function parseDevicePairingQr(raw: string): DevicePairing | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof parsed !== 'object' || parsed === null) {
    return null;
  }
  const obj = parsed as Record<string, unknown>;
  if (typeof obj.deviceName !== 'string' || typeof obj.pairingToken !== 'string') {
    return null;
  }
  if (!obj.deviceName || !obj.pairingToken) {
    return null;
  }
  return { deviceName: obj.deviceName, pairingToken: obj.pairingToken };
}
