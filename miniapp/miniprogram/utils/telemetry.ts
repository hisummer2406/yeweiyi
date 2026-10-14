import { roundFields } from './format';
import { TANK_ALARM_STATE_LABELS, label } from './labels';
import { tankFill } from './tankFill';
import type { TankFill } from './tankFill';
import { tankKey } from './stationDevice';
import type { LatestTelemetryResponse, Tank } from '../types/api';

// wx:for item interpolation in WXML cannot call a function, so the Chinese label has to already be
// a plain field on the row by the time it reaches page data.
// `key` is device_name + tank number (utils/stationDevice.ts tankKey): the identity of this tank's data.
// The fill fields drive the level fill drawn over the tank illustration (home page): see tankFill.
export type TankView = Tank & { alarmStateLabel: string; key: string } & TankFill;

// Shared by the home page and device-detail so the two cannot disagree about how a snapshot is
// turned into rows. Pure (no `wx`), which is also what makes it checkable in plain Node.
const TANK_NUMBER_FIELDS = [
  'oilLevelMm',
  'waterLevelMm',
  'tempC',
  'oilVolumeL',
  'waterVolumeL',
  'oilWeightKg',
  'nullVolumeL',
  'oilRatioPct',
  'waterRatioPct',
] as const;

// `deviceName` is the device the snapshot was REQUESTED for, not read back from the body.
export function toTankViews(res: LatestTelemetryResponse, deviceName: string): TankView[] {
  return (res.tanks || []).map((t) => ({
    ...roundFields(t, TANK_NUMBER_FIELDS),
    alarmStateLabel: label(TANK_ALARM_STATE_LABELS, t.alarmState),
    key: tankKey(deviceName, t.index),
    ...tankFill(t),
  }));
}

// GET /api/devices/:device/telemetry/latest answers 404 for "authorized and provisioned, but has
// never reported" — a real state, not an error (see types/api.d.ts). Anything else is a failure.
export function isNeverReported(err: { status?: number }): boolean {
  return err.status === 404;
}
