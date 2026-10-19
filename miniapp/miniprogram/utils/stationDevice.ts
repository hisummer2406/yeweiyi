import type { Device } from '../types/api';

// Station ↔ device association under the one-screen-per-station rule (yeweiyi-cloud
// docs/station-device-replacement-contract.md v1.0 §3.3, §5, §6). Pure (no `wx`), checked in Node by
// `npm run check`.
//
// There is no "read by station" endpoint: GET /api/devices rows carry `station_id`, the app finds the
// station's current device from that, and every data call after that uses `device_name` alone. Data is
// never merged across devices by station_id or tank number.

// What a screen opened on one device should do after the device list was re-read. `stationId` is the
// station the screen was opened under (null = the device was in no station, undefined = unknown).
export type FollowDecision =
  | { kind: 'same' }
  // The station's current device is a different one (replaced in the back office): switch to it,
  // dropping everything held for the old one.
  | { kind: 'switch'; device: string }
  // Nothing to show: the station has no device this user may see, or the device left their scope.
  // `reason` picks the wording; neither case offers any way to self-authorize.
  | { kind: 'none'; reason: 'no-device' | 'ambiguous' | 'device-gone' };

export function stationIdOf(devices: Device[], deviceName: string): number | null | undefined {
  const row = devices.find((d) => d.device_name === deviceName);
  if (!row) return undefined;
  return typeof row.station_id === 'number' ? row.station_id : null;
}

export function followStationDevice(
  devices: Device[],
  deviceName: string,
  stationId: number | null | undefined,
): FollowDecision {
  const current = devices.find((d) => d.device_name === deviceName);
  if (typeof stationId !== 'number') {
    // Opened outside any station (or an older cloud with no station data): nothing to follow, only
    // check that the device is still in this user's scope.
    return current ? { kind: 'same' } : { kind: 'none', reason: 'device-gone' };
  }
  if (current && current.station_id === stationId) return { kind: 'same' };

  const inStation = devices.filter((d) => d.station_id === stationId);
  if (inStation.length === 1) return { kind: 'switch', device: inStation[0].device_name };
  // More than one is the pre-migration state the cloud's migration refuses to start from. Picking one
  // would be a guess, and merging them is exactly what the contract forbids.
  if (inStation.length > 1) return { kind: 'none', reason: 'ambiguous' };
  return { kind: 'none', reason: 'no-device' };
}

// i18n keys (locales/*.ts, group `follow`): this module is pure, the page renders them with `t()`.
export const FOLLOW_NOTICES: Record<'no-device' | 'ambiguous' | 'device-gone', string> = {
  'no-device': 'follow.noDevice',
  ambiguous: 'follow.ambiguous',
  'device-gone': 'follow.deviceGone',
};

// The same three reasons as a command refusal: the notice plus "the command was not sent".
const COMMAND_BLOCKED: Record<'no-device' | 'ambiguous' | 'device-gone', string> = {
  'no-device': 'follow.cmdNoDevice',
  ambiguous: 'follow.cmdAmbiguous',
  'device-gone': 'follow.cmdDeviceGone',
};

// Identity of one tank's data and of anything cached for it: the device name AND the tank number.
// Neither alone is an identity (the same tank number on another device is another object), and the
// device name is an opaque string — never parsed, padded or trimmed, so leading zeros survive.
export function tankKey(deviceName: string, tank: number | string): string {
  return `${encodeURIComponent(deviceName)}/${String(tank)}`;
}

// Station id behind a home-page station key or a `station` query value: a number, null for "in no
// station" (the home page's 'ungrouped', the query's 'none'), undefined for "not known".
export function stationIdFromKey(key: string | undefined): number | null | undefined {
  if (key === undefined || key === '') return undefined;
  if (key === 'none' || key === 'ungrouped') return null;
  return /^\d+$/.test(key) ? Number(key) : undefined;
}

// Query value for pages opened from a station-scoped list: the numeric id, or 'none' for ungrouped.
export function stationParam(stationId: number | null | undefined): string {
  if (typeof stationId === 'number') return String(stationId);
  return stationId === null ? 'none' : '';
}

// Last check before a control command leaves the phone (miniapp#7, review of the station-scoped command
// form). `devices` is a fresh GET /api/devices, `deviceName` the device the form was opened for and
// `stationId` the station the detail page was opened under (undefined = opened by device name alone:
// then only "still in this user's scope" can be checked). Anything but "same" blocks the command — it is
// never redirected to the station's new device, because the user confirmed this one, not that one.
// A check that cannot be made (the read failed) must block too; the caller does that.
export type CommandGuard = { ok: true } | { ok: false; messageKey: string };

export const COMMAND_REPLACED_NOTICE_KEY = 'follow.cmdReplaced';

export function commandGuard(
  devices: Device[],
  deviceName: string,
  stationId: number | null | undefined,
): CommandGuard {
  const decision = followStationDevice(devices, deviceName, stationId);
  if (decision.kind === 'same') return { ok: true };
  if (decision.kind === 'switch') return { ok: false, messageKey: COMMAND_REPLACED_NOTICE_KEY };
  return { ok: false, messageKey: COMMAND_BLOCKED[decision.reason] };
}
