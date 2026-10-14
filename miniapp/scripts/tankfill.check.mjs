// Run with: npm run check   (node --experimental-strip-types; pure logic only, no wx)
import assert from 'node:assert/strict';
import { tankFill } from '../miniprogram/utils/tankFill.ts';

let n = 0;
const test = (name, fn) => {
  fn();
  n++;
  console.log('ok -', name);
};
const tank = (o) => ({ oilRatioPct: 0, waterRatioPct: 0, alarmState: 'NORMAL', probeConnected: true, ...o });

test('oil sits on top of water and both come from the ratios', () => {
  assert.deepEqual(tankFill(tank({ oilRatioPct: 60, waterRatioPct: 5 })), {
    fillOilPct: 60,
    fillWaterPct: 5,
    fillState: 'normal',
  });
});

test('oil and water never add up to more than the whole tank', () => {
  const f = tankFill(tank({ oilRatioPct: 90, waterRatioPct: 30 }));
  assert.equal(f.fillWaterPct, 30);
  assert.equal(f.fillOilPct, 70);
});

test('a bad reading is clamped or dropped, never drawn as a broken shape', () => {
  assert.deepEqual(tankFill(tank({ oilRatioPct: 140 })).fillOilPct, 100);
  assert.deepEqual(tankFill(tank({ oilRatioPct: -5 })).fillOilPct, 0);
  assert.deepEqual(tankFill(tank({ oilRatioPct: NaN, waterRatioPct: NaN })).fillOilPct, 0);
});

test('alarm state picks the colour; no probe draws nothing', () => {
  assert.equal(tankFill(tank({ alarmState: 'ALARM' })).fillState, 'alarm');
  assert.equal(tankFill(tank({ alarmState: 'FAULT' })).fillState, 'fault');
  assert.equal(tankFill(tank({ alarmState: '' })).fillState, 'normal');
  assert.deepEqual(tankFill(tank({ probeConnected: false, oilRatioPct: 50 })), {
    fillOilPct: 0,
    fillWaterPct: 0,
    fillState: 'none',
  });
});

console.log(`${n} checks passed`);
