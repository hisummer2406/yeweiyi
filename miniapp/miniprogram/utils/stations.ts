import type { Device, Station } from '../types/api';

// Pure (no `wx`), so it can be checked in plain Node: `npm run check`.

export const UNGROUPED_KEY = 'ungrouped';

export interface StationOption {
  // String(station.id), or UNGROUPED_KEY for devices that are in no (known) station.
  key: string;
  name: string;
  address: string;
  deviceCount: number;
}

function knownIds(stations: Station[]): Set<number> {
  return new Set(stations.map((s) => s.id));
}

// A device is in a station only when its station_id is a number the station list knows. null, a
// missing key (older cloud), and an id the list does not contain all mean "ungrouped".
function keyOfDevice(device: Device, known: Set<number>): string {
  const id = device.station_id;
  return typeof id === 'number' && known.has(id) ? String(id) : UNGROUPED_KEY;
}

// Options for the home-page dropdown. An EMPTY result means "do not show a dropdown": there are no
// stations, or no device sits in a known station (older cloud, or a customer admin whose stations are
// all still empty). That is the degraded, flat view the home page had before stations existed.
// `ungroupedName` is the display name of the "no station" entry (language of the page).
export function buildStationOptions(
  stations: Station[],
  devices: Device[],
  ungroupedName = 'Ungrouped',
): StationOption[] {
  const known = knownIds(stations);
  const counts = new Map<string, number>();
  for (const d of devices) {
    const key = keyOfDevice(d, known);
    counts.set(key, (counts.get(key) || 0) + 1);
  }
  const grouped = devices.length - (counts.get(UNGROUPED_KEY) || 0);
  if (stations.length === 0 || grouped === 0) return [];

  const options: StationOption[] = stations.map((s) => ({
    key: String(s.id),
    name: s.name,
    address: s.address,
    deviceCount: counts.get(String(s.id)) || 0,
  }));
  const ungrouped = counts.get(UNGROUPED_KEY) || 0;
  if (ungrouped > 0) {
    options.push({ key: UNGROUPED_KEY, name: ungroupedName, address: '', deviceCount: ungrouped });
  }
  return options;
}

export function devicesOfStation(devices: Device[], stations: Station[], key: string): Device[] {
  const known = knownIds(stations);
  return devices.filter((d) => keyOfDevice(d, known) === key);
}

// The remembered station if it is still an option; otherwise the first station that has devices (a
// customer admin's list can start with an empty station, and landing on it would look like "no
// devices"); otherwise the first option.
export function pickStation(options: StationOption[], remembered?: string): StationOption | undefined {
  if (options.length === 0) return undefined;
  const hit = remembered ? options.find((o) => o.key === remembered) : undefined;
  return hit || options.find((o) => o.deviceCount > 0) || options[0];
}

export function filterStations(options: StationOption[], query: string): StationOption[] {
  const q = query.trim().toLowerCase();
  if (!q) return options;
  return options.filter((o) => o.name.toLowerCase().includes(q) || o.address.toLowerCase().includes(q));
}

// Which device the 我的记录 pages show (miniapp#16).
//
// The records pages follow the home page's current station: the same options and the same pick rule
// (above), fed the same remembered key, so the two cannot disagree. A station has at most one
// device (station-device-replacement-contract); records are never merged across devices or stations,
// and no device is ever guessed — there is no device picker on these pages.
export type RecordsContext =
  // `lost`: the remembered station is no longer offered and another one was picked (as on the home page).
  | { kind: 'ok'; stationKey: string; stationName: string; device: Device; lost: boolean }
  // No usable station: the cloud has none (or is older), or the current pick is "ungrouped".
  | { kind: 'no-station' }
  | { kind: 'empty-station'; stationKey: string; stationName: string }
  // More than one device in one station: a data problem the contract forbids; never pick one.
  | { kind: 'ambiguous'; stationKey: string; stationName: string };

// `pinned`: a station key the page was opened for (an old device-detail link that was verified); it is
// used instead of the remembered one and, if it is no longer offered, there is no fallback.
export function recordsContext(
  devices: Device[],
  stations: Station[],
  remembered: string | undefined,
  pinned?: string,
): RecordsContext {
  const options = buildStationOptions(stations, devices);
  const current = pinned ? options.find((o) => o.key === pinned) : pickStation(options, remembered);
  if (!current || current.key === UNGROUPED_KEY) return { kind: 'no-station' };
  const base = { stationKey: current.key, stationName: current.name };
  const inStation = devicesOfStation(devices, stations, current.key);
  if (inStation.length === 0) return { kind: 'empty-station', ...base };
  if (inStation.length > 1) return { kind: 'ambiguous', ...base };
  const lost = !pinned && !!remembered && remembered !== current.key && /^\d+$/.test(remembered);
  return { kind: 'ok', ...base, device: inStation[0], lost };
}
