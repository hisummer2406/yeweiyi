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

// The scene of the device page currently open from a mini-program code, so a session that dies while
// that page is open (401 mid-use) can come back to the same device after login. Held only while that
// page exists (device-detail sets it on enter and clears it on unload); never persisted.
let activeScene: string | null = null;

export function setActiveScene(scene: string | null): void {
  activeScene = scene;
}

export function loginUrlForSession(): string {
  return activeScene ? loginUrlForScene(activeScene) : LOGIN;
}

// How the device page was opened, decided from its launch query alone:
//  - scene: a mini-program code; the device must be located first (POST /api/devices/resolve);
//  - station: opened from a station on the home page; the station's current device must be decided
//    (GET /api/devices) before anything is asked for or subscribed to the device name that was passed;
//  - device: opened by device name alone (我的记录), an independent view of that device;
//  - invalid: neither a device nor a usable scene. Nothing may be queried or subscribed — an empty
//    device name would open /ws/alarms?device= and reconnect forever.
export type EntryKind =
  | { kind: 'scene'; scene: string }
  | { kind: 'station'; device: string; stationId: number }
  | { kind: 'device'; device: string }
  | { kind: 'invalid' };

export function classifyEntry(query: Record<string, string | undefined> | undefined): EntryKind {
  const device = typeof query?.device === 'string' ? query.device : '';
  if (!device) {
    const scene = sceneFromQuery(query);
    return scene ? { kind: 'scene', scene } : { kind: 'invalid' };
  }
  // Same reading as stationDevice.stationIdFromKey: a numeric id is a station; 'none'/absent is not.
  const station = query?.station;
  return typeof station === 'string' && /^\d+$/.test(station)
    ? { kind: 'station', device, stationId: Number(station) }
    : { kind: 'device', device };
}
