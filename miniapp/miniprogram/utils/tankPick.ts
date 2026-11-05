// Which tank a single-tank screen shows (miniapp#15). Pure (no `wx`), checked in Node by `npm run check`.
//
// A tank is identified by the device name AND its protocol tank number (utils/stationDevice.ts tankKey),
// never by its position in a list: positions shift when a grant changes or the station's device is
// replaced, and would then silently point at another tank.

// The tanks this user can see on one device: the snapshot's tank numbers (the cloud already sends only
// granted tanks), intersected with the row's `granted_tanks` when the cloud gives one (customer_user) as
// a second guard. Ascending, without duplicates.
export function visibleTanks(snapshotTanks: number[], grantedTanks?: number[] | null): number[] {
  const granted = Array.isArray(grantedTanks) ? new Set(grantedTanks) : null;
  const out = Array.from(new Set(snapshotTanks)).filter((n) => Number.isInteger(n) && (!granted || granted.has(n)));
  return out.sort((a, b) => a - b);
}

// The `tank` query value: a non-negative integer, anything else is "not given".
export function parseTankParam(value: string | undefined): number | undefined {
  return value !== undefined && /^\d+$/.test(value) ? Number(value) : undefined;
}

// `hidden` is set when a tank was asked for but is not visible (grant revoked, tank gone, or another
// device's tank number): the page says so (i18n `detail.tankHidden`) and shows the first visible tank.
export type TankPick = { kind: 'tank'; tank: number; hidden?: number } | { kind: 'none'; hidden?: number };

export function pickTank(visible: number[], requested?: number): TankPick {
  if (requested !== undefined && visible.includes(requested)) return { kind: 'tank', tank: requested };
  const hidden = requested;
  if (visible.length === 0) return hidden === undefined ? { kind: 'none' } : { kind: 'none', hidden };
  const first = Math.min(...visible);
  return hidden === undefined ? { kind: 'tank', tank: first } : { kind: 'tank', tank: first, hidden };
}
