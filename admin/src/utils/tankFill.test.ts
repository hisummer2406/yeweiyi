import { describe, expect, it } from 'vitest';
import { tankFill } from './tankFill';

const t = (p: Partial<API.Tank> = {}) => ({
  oilRatioPct: 50,
  waterRatioPct: 5,
  alarmState: 'NORMAL',
  probeConnected: true,
  ...p,
});

describe('tankFill', () => {
  it("uses the tank's own ratios, water at the bottom, oil above", () => {
    expect(tankFill(t())).toEqual({
      fillOilPct: 50,
      fillWaterPct: 5,
      fillState: 'normal',
    });
  });
  it('draws nothing for a tank without a probe, whatever the numbers say', () => {
    expect(tankFill(t({ probeConnected: false }))).toEqual({
      fillOilPct: 0,
      fillWaterPct: 0,
      fillState: 'none',
    });
  });
  it('never lets oil and water add up to more than the tank', () => {
    const f = tankFill(t({ oilRatioPct: 90, waterRatioPct: 30 }));
    expect(f.fillWaterPct).toBe(30);
    expect(f.fillOilPct).toBe(70);
  });
  it('clamps bad readings instead of drawing a broken shape', () => {
    expect(tankFill(t({ oilRatioPct: 250, waterRatioPct: -4 }))).toMatchObject({
      fillOilPct: 100,
      fillWaterPct: 0,
    });
    expect(
      tankFill(t({ oilRatioPct: Number.NaN, waterRatioPct: Number.NaN })),
    ).toMatchObject({ fillOilPct: 0, fillWaterPct: 0 });
  });
  it('keeps one decimal and marks alarm and fault', () => {
    expect(tankFill(t({ oilRatioPct: 33.333 })).fillOilPct).toBe(33.3);
    expect(tankFill(t({ alarmState: 'ALARM' })).fillState).toBe('alarm');
    expect(tankFill(t({ alarmState: 'FAULT' })).fillState).toBe('fault');
  });
});
