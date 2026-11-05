import type { Tank } from '../types/api';

export interface TankFill {
  fillOilPct: number;
  fillWaterPct: number;
  fillState: 'normal' | 'alarm' | 'fault' | 'none';
}

// Level fill for the tank picture, from the tank's own ratios (oilRatioPct / waterRatioPct, share of
// the tank's capacity). Water sits at the bottom with the oil on top of it, so the two never overlap
// and never add up to more than the whole tank. A bad reading is clamped, not trusted: a ratio
// outside 0..100 or not a number draws nothing rather than a broken shape. A tank whose probe is not
// connected has no reading at all, so nothing is drawn (the card already says 探针未连接).
export function tankFill(
  t: Pick<Tank, 'oilRatioPct' | 'waterRatioPct' | 'alarmState' | 'probeConnected'>,
): TankFill {
  const clamp = (n: number) => (Number.isFinite(n) ? Math.min(100, Math.max(0, n)) : 0);
  if (!t.probeConnected) return { fillOilPct: 0, fillWaterPct: 0, fillState: 'none' };
  const water = clamp(t.waterRatioPct);
  const oil = Math.min(clamp(t.oilRatioPct), 100 - water);
  const fillState = t.alarmState === 'ALARM' ? 'alarm' : t.alarmState === 'FAULT' ? 'fault' : 'normal';
  return { fillOilPct: Math.round(oil * 10) / 10, fillWaterPct: Math.round(water * 10) / 10, fillState };
}
