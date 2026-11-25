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

test('alarm state picks the colour', () => {
  assert.equal(tankFill(tank({ alarmState: 'ALARM' })).fillState, 'alarm');
  assert.equal(tankFill(tank({ alarmState: 'FAULT' })).fillState, 'fault');
  assert.equal(tankFill(tank({ alarmState: '' })).fillState, 'normal');
});

// #75: probeConnected=false now only arrives after the device's own disconnect threshold. The tank keeps
// its last level, drawn grey, whatever the alarm state; a real level of 0 is an empty tank, not "no probe".
test('a lost probe keeps the last level, drawn as stale', () => {
  assert.deepEqual(tankFill(tank({ probeConnected: false, oilRatioPct: 50, waterRatioPct: 4 })), {
    fillOilPct: 50,
    fillWaterPct: 4,
    fillState: 'stale',
  });
  assert.equal(tankFill(tank({ probeConnected: false, alarmState: 'FAULT', oilRatioPct: 30 })).fillState, 'stale');
});

test('normal -> lost -> recovered follows probeConnected frame by frame', () => {
  const frames = [true, false, true].map((probeConnected) => tankFill(tank({ probeConnected, oilRatioPct: 42 })));
  assert.deepEqual(frames.map((f) => f.fillState), ['normal', 'stale', 'normal']);
  assert.ok(frames.every((f) => f.fillOilPct === 42));
});

test('a tank never read since boot draws nothing; old firmware without the field is unaffected', () => {
  assert.deepEqual(tankFill(tank({ hasReading: false, oilRatioPct: 0 })), { fillOilPct: 0, fillWaterPct: 0, fillState: 'none' });
  assert.equal(tankFill(tank({ hasReading: false, probeConnected: false })).fillState, 'none');
  assert.equal(tankFill(tank({ hasReading: true, oilRatioPct: 0 })).fillState, 'normal');
  assert.equal(tankFill(tank({ oilRatioPct: 10 })).fillState, 'normal');
});

test('a connected tank at level 0 is drawn empty and normal', () => {
  assert.deepEqual(tankFill(tank({ oilRatioPct: 0, waterRatioPct: 0 })), { fillOilPct: 0, fillWaterPct: 0, fillState: 'normal' });
});

console.log(`${n} checks passed`);
