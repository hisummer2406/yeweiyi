import type { Tank } from '../types/api';

export interface TankFill {
  fillOilPct: number;
  fillWaterPct: number;
  fillState: 'normal' | 'alarm' | 'fault' | 'stale' | 'none';
}

// Level fill for the tank picture, from the tank's own ratios (oilRatioPct / waterRatioPct, share of
// the tank's capacity). Water sits at the bottom with the oil on top of it, so the two never overlap
// and never add up to more than the whole tank. A bad reading is clamped, not trusted: a ratio
// outside 0..100 or not a number draws nothing rather than a broken shape. A tank whose probe is lost
// (the device reports it only after its own disconnect threshold, #75) keeps its last level, drawn
// grey as 'stale'; the card says 通讯异常 and marks the numbers as the last reading. A tank the device
// has never read (hasReading=false) has no level to draw at all: 'none'.
export function tankFill(
  t: Pick<Tank, 'oilRatioPct' | 'waterRatioPct' | 'alarmState' | 'probeConnected' | 'hasReading'>,
): TankFill {
  if (t.hasReading === false) return { fillOilPct: 0, fillWaterPct: 0, fillState: 'none' };
  const clamp = (n: number) => (Number.isFinite(n) ? Math.min(100, Math.max(0, n)) : 0);
  const water = clamp(t.waterRatioPct);
  const oil = Math.min(clamp(t.oilRatioPct), 100 - water);
  const fillState = !t.probeConnected
    ? 'stale'
    : t.alarmState === 'ALARM' ? 'alarm' : t.alarmState === 'FAULT' ? 'fault' : 'normal';
  return { fillOilPct: Math.round(oil * 10) / 10, fillWaterPct: Math.round(water * 10) / 10, fillState };
}
