import { TANK_ALARM_STATE_LABELS, label } from './labels';
import type { LatestTelemetryResponse, Tank } from '../types/api';

// wx:for item interpolation in WXML cannot call a function, so the Chinese label has to already be
// a plain field on the row by the time it reaches page data.
export type TankView = Tank & { alarmStateLabel: string };

// Shared by the home page and device-detail so the two cannot disagree about how a snapshot is
// turned into rows. Pure (no `wx`), which is also what makes it checkable in plain Node.
export function toTankViews(res: LatestTelemetryResponse): TankView[] {
  return (res.tanks || []).map((t) => ({
    ...t,
    alarmStateLabel: label(TANK_ALARM_STATE_LABELS, t.alarmState),
  }));
}

// GET /api/devices/:device/telemetry/latest answers 404 for "authorized and provisioned, but has
// never reported" — a real state, not an error (see types/api.d.ts). Anything else is a failure.
export function isNeverReported(err: { status?: number }): boolean {
  return err.status === 404;
}
