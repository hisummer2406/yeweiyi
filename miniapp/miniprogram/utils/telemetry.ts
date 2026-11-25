import { formatLocalTime, roundFields } from './format';
import { currentLocale, t as tr } from './i18n';
import { TANK_ALARM_STATE_KEYS, label } from './labels';
import { tankFill } from './tankFill';
import { tankShapeOf, type TankShapeState } from './tankShape';
import type { TankFill } from './tankFill';
import { tankKey } from './stationDevice';
import { applyVirtual, rawOf, type Virtualized } from './virtualTable';
import type { TankTable } from './tankTable';
import type { LatestTelemetryResponse, Tank } from '../types/api';

// wx:for item interpolation in WXML cannot call a function, so the labels (in the current language)
// have to already be plain fields on the row by the time it reaches page data.
// `key` is device_name + tank number (utils/stationDevice.ts tankKey): the identity of this tank's data.
// The fill fields drive the level fill drawn over the tank illustration (home page): see tankFill.
// `raw` keeps the unrounded device readings and `virtualState` says whether volume/mass/ullage/water volume are
// read from this phone's virtual tank table (#56, utils/virtualTable.ts); the fill is drawn from the device's
// ratios, which a table does not change.
// 首页和详情实时数据 tab 可见时静默重读 telemetry/latest 的间隔；设备液位变化时最快每 5 秒上报一次。
export const LIVE_REFRESH_MS = 5_000;

export type TankView = Tank & { alarmStateLabel: string; tankNoLabel: string; key: string; noReading: boolean } & TankFill & ReturnType<typeof tankShapeFields> & Virtualized;

export function tankShapeFields(state: TankShapeState | undefined, tank: number) {
  const info = tankShapeOf(state, tank);
  return {
    shape: info.shape,
    oilColor: info.oilColor,
    shapeLabel: tr(`tankDisplay.${info.shape}`),
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
// `tableOf` gives this phone's virtual tank table of a tank (null = device readings).
export function toTankViews(
  res: LatestTelemetryResponse,
  deviceName: string,
  shapes?: TankShapeState,
  tableOf?: (tank: number) => TankTable | null,
): TankView[] {
  // A tank never read (#75) has only placeholder zeros: no table lookup, so no 虚拟罐显示 / 设备原值 tag either.
  return (res.tanks || []).map((t) => applyVirtual({
    ...roundFields(t, TANK_NUMBER_FIELDS),
    raw: rawOf(t as unknown as Record<string, unknown>),
    virtualState: '',
    virtualReason: '',
    alarmStateLabel: label(TANK_ALARM_STATE_KEYS, t.alarmState),
    tankNoLabel: tr('tank.no', { n: t.index }),
    key: tankKey(deviceName, t.index),
    // #75: never read since boot — the readings are placeholder zeros and are shown as dashes.
    noReading: t.hasReading === false,
    ...tankFill(t),
    ...tankShapeFields(shapes, t.index),
  }, t.hasReading === false ? null : tableOf?.(t.index) ?? null));
}

// A failed telemetry/latest read is classified by utils/failureState.ts telemetryFailure: a 404 is
// never reported, past the retention period, or not set up on the platform, told apart by `code`.
