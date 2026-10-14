// Entry through a device-specific mini-program code (yeweiyi-cloud tank-grants-contract v1.0 §3):
// path pages/device-detail/device-detail, scene = the device name as printed (≤32 chars, no encoding
// needed). Pure (no `wx`), so it can be checked in plain Node: `npm run check`.

const DETAIL = '/pages/device-detail/device-detail';
const LOGIN = '/pages/login/login';

// The code carried by a page's launch query, or null when there is none / it is not a bare code.
// WeChat hands `scene` over URL-encoded, so it is decoded once; a malformed escape is "no scene"
// rather than an exception. Nothing is padded, trimmed of zeros or prefixed: the cloud resolves it.
export function sceneFromQuery(query: Record<string, string | undefined> | undefined): string | null {
  const raw = query?.scene;
  if (typeof raw !== 'string' || raw === '') return null;
  let decoded: string;
  try {
    decoded = decodeURIComponent(raw);
  } catch {
    return null;
  }
  // Same shape the cloud accepts for a scanned label (utils/pairing.ts parseDeviceCodeQr).
  const code = decoded.trim();
  return /^[0-9A-Za-z_-]{1,64}$/.test(code) ? code : null;
}

// Not signed in yet: go to login carrying the scene, so signing in lands on that device.
export function loginUrlForScene(scene: string): string {
  return `${LOGIN}?scene=${encodeURIComponent(scene)}`;
}

// Where login goes next: the scanned device if the login was started by one, else the home tab.
export function detailUrlForScene(scene: string): string {
  return `${DETAIL}?scene=${encodeURIComponent(scene)}`;
}
