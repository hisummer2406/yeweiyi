import { formatLocalTime, roundFields } from './format';
import { currentLocale, t as tr } from './i18n';
import { TANK_ALARM_STATE_KEYS, label } from './labels';
import { tankFill } from './tankFill';
import { tankShapeOf, type TankShapeState } from './tankShape';
import type { TankFill } from './tankFill';
import { tankKey } from './stationDevice';
import type { LatestTelemetryResponse, Tank } from '../types/api';

// wx:for item interpolation in WXML cannot call a function, so the labels (in the current language)
// have to already be plain fields on the row by the time it reaches page data.
// `key` is device_name + tank number (utils/stationDevice.ts tankKey): the identity of this tank's data.
// The fill fields drive the level fill drawn over the tank illustration (home page): see tankFill.
export type TankView = Tank & { alarmStateLabel: string; tankNoLabel: string; key: string } & TankFill & ReturnType<typeof tankShapeFields>;

export function tankShapeFields(state: TankShapeState | undefined, tank: number) {
  const info = tankShapeOf(state, tank);
  const label = tr(`tankDisplay.${info.status === 'error' ? 'failed' : info.shape}`);
  return {
    shape: info.shape,
    shapeLabel: info.status === 'stale' ? `${label} · ${tr('tankDisplay.stale')}` : label,
    // 未确认罐型时保留原有通用罐图；展示回退不改变真实的 unknown 状态。
    shapePictureLabel: info.shape === 'unknown' ? `${tr('tankDisplay.generic')} · ${label}` : label,
    shapeTime: info.reportedAt ? tr('tankDisplay.reportedAt', { time: formatLocalTime(info.reportedAt, currentLocale()) }) : '',
    shapeImage: `/assets/illustrations/${info.shape === 'vertical' ? 'tank-vertical-home.png' : 'tank-horizontal-home.png'}`,
  };
}

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
export function toTankViews(res: LatestTelemetryResponse, deviceName: string, shapes?: TankShapeState): TankView[] {
  return (res.tanks || []).map((t) => ({
    ...roundFields(t, TANK_NUMBER_FIELDS),
    alarmStateLabel: label(TANK_ALARM_STATE_KEYS, t.alarmState),
    tankNoLabel: tr('tank.no', { n: t.index }),
    key: tankKey(deviceName, t.index),
    ...tankFill(t),
    ...tankShapeFields(shapes, t.index),
  }));
}

// A failed telemetry/latest read is classified by utils/failureState.ts telemetryFailure: a 404 is
// never reported, past the retention period, or not set up on the platform, told apart by `code`.
