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
