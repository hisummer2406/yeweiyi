// Run with: npm run check   (node --experimental-strip-types)
// 本机显示倍率（Issue #44）：纯规则 + 存储层（内存 wx 替身、伪造 JWT）。不是微信真机或 Donut 验收。
import assert from 'node:assert/strict';
import { register } from 'node:module';

register(
  'data:text/javascript,' +
    encodeURIComponent(`
export async function resolve(spec, ctx, next) {
  try { return await next(spec, ctx); }
  catch (e) {
    if ((spec.startsWith('./') || spec.startsWith('../')) && !spec.endsWith('.ts')) return next(spec + '.ts', ctx);
    throw e;
  }
}`),
);

const store = new Map();
let failWrites = false;
globalThis.wx = {
  getStorageSync: (k) => (store.has(k) ? structuredClone(store.get(k)) : ''),
  setStorageSync: (k, v) => {
    if (failWrites) throw new Error('storage full');
    store.set(k, structuredClone(v));
  },
  removeStorageSync: (k) => {
    if (failWrites) throw new Error('storage full');
    store.delete(k);
  },
  base64ToArrayBuffer: (b64) => {
    const buf = Buffer.from(b64, 'base64');
    return buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength);
  },
};

const S = await import('../miniprogram/utils/displayScale.ts');
const Store = await import('../miniprogram/utils/displayScaleStore.ts');

const jwt = (claims) => `h.${Buffer.from(JSON.stringify(claims)).toString('base64url')}.s`;
const login = (claims) => store.set('token', jwt(claims));

let n = 0;
const test = (name, fn) => {
  store.clear();
  failWrites = false;
  fn();
  n++;
  console.log('ok -', name);
};

const view = (raw) => ({ ...raw, raw: S.rawOf(raw), scaleTag: '', oilLevelMm: 1200, tempC: 21.5, oilRatioPct: 40 });

test('1000 L / 800 kg × 1.5 → 1500 L / 1200 kg; level, temp, ratio untouched', () => {
  const v = S.applyScale(view({ oilVolumeL: 1000, oilWeightKg: 800, waterVolumeL: 20, nullVolumeL: 500 }), 1.5);
  assert.equal(v.oilVolumeL, 1500);
  assert.equal(v.oilWeightKg, 1200);
  assert.equal(v.waterVolumeL, 30);
  assert.equal(v.nullVolumeL, 750);
  assert.equal(v.oilLevelMm, 1200);
  assert.equal(v.tempC, 21.5);
  assert.equal(v.oilRatioPct, 40);
  assert.equal(v.scaleTag, '×1.5');
});

test('applying again never multiplies twice; off shows the original', () => {
  const once = S.applyScale(view({ oilVolumeL: 1000 }), 1.5);
  const twice = S.applyScale(once, 1.5);
  assert.equal(twice.oilVolumeL, 1500);
  const off = S.applyScale(twice, undefined);
  assert.equal(off.oilVolumeL, 1000);
  assert.equal(off.scaleTag, '');
});

test('scaled from the unrounded value, rounded after', () => {
  const v = S.applyScale(view({ oilVolumeL: 1000.333 }), 3);
  assert.equal(v.oilVolumeL, 3001);
  assert.equal(S.applyScale(view({ oilVolumeL: 1000.333 }), undefined).oilVolumeL, 1000.33);
});

test('missing readings stay missing, never 0', () => {
  const v = S.applyScale(view({ oilVolumeL: 1000 }), 2);
  assert.equal(v.oilWeightKg, undefined);
  assert.equal(v.nullVolumeL, undefined);
});

test('factor parsing: positive decimals only', () => {
  assert.equal(S.parseFactor('1.5'), 1.5);
  assert.equal(S.parseFactor(' 2 '), 2);
  assert.equal(S.parseFactor('.5'), 0.5);
  assert.equal(S.parseFactor('2.'), 2);
  for (const bad of ['', '0', '0.0', '-1', 'abc', '1e3', 'Infinity', 'NaN', '1.2.3', '1,5']) {
    assert.equal(S.parseFactor(bad), null, bad);
  }
});

test('stored value is validated: corrupt → default', () => {
  assert.deepEqual(S.readConfig({ enabled: true, factor: 1.5 }), { enabled: true, factor: 1.5 });
  for (const bad of ['', null, 'x', { enabled: true }, { enabled: 'yes', factor: 2 }, { enabled: true, factor: 0 }, { enabled: true, factor: -2 }, { enabled: true, factor: Infinity }]) {
    assert.deepEqual(S.readConfig(bad), S.DEFAULT_SCALE);
  }
});

test('only administrators may use it', () => {
  assert.equal(S.canUseScale('platform_admin'), true);
  assert.equal(S.canUseScale('customer_admin'), true);
  assert.equal(S.canUseScale('customer_user'), false);
  assert.equal(S.canUseScale(''), false);
});

test('key = account + full device name + tank; incomplete → no key', () => {
  assert.equal(S.scaleKey('u7', 'DEV-001', 2), 'display-scale:u7:DEV-001#2');
  assert.equal(S.scaleKey('', 'DEV-001', 2), '');
  assert.equal(S.scaleKey('u7', '', 2), '');
  assert.equal(S.scaleKey('u7', 'DEV-001', -1), '');
});

test('admin saves and gets it back; default is ×1 off', () => {
  login({ uid: 7, role: 'customer_admin' });
  assert.deepEqual(Store.loadScale('DEV-A', 1), S.DEFAULT_SCALE);
  assert.equal(Store.scaleFactor('DEV-A', 1), undefined);
  assert.equal(Store.saveScale('DEV-A', 1, { enabled: true, factor: 1.5 }), true);
  assert.equal(Store.scaleFactor('DEV-A', 1), 1.5);
  assert.equal(Store.saveScale('DEV-A', 1, { enabled: false, factor: 1.5 }), true);
  assert.equal(Store.scaleFactor('DEV-A', 1), undefined);
  assert.deepEqual(Store.loadScale('DEV-A', 1), { enabled: false, factor: 1.5 });
});

test('isolated by device and tank: same tank number on another device, another tank', () => {
  login({ uid: 7, role: 'platform_admin' });
  Store.saveScale('DEV-A', 1, { enabled: true, factor: 2 });
  assert.equal(Store.scaleFactor('DEV-B', 1), undefined);
  assert.equal(Store.scaleFactor('DEV-A', 2), undefined);
  assert.equal(Store.scaleFactor('DEV-A', 1), 2);
});

test('another account, staff, or a role taken away never reads or applies it', () => {
  login({ uid: 7, role: 'customer_admin' });
  Store.saveScale('DEV-A', 1, { enabled: true, factor: 2 });
  login({ uid: 8, role: 'customer_admin' });
  assert.equal(Store.scaleFactor('DEV-A', 1), undefined);
  login({ uid: 7, role: 'customer_user' });
  assert.equal(Store.scaleFactor('DEV-A', 1), undefined);
  assert.equal(Store.scaleAllowed('DEV-A', 1), false);
  assert.equal(Store.saveScale('DEV-A', 1, { enabled: true, factor: 3 }), false);
  login({ uid: 7 });
  assert.equal(Store.scaleFactor('DEV-A', 1), undefined);
  store.delete('token');
  assert.equal(Store.scaleFactor('DEV-A', 1), undefined);
  login({ uid: 7, role: 'customer_admin' });
  assert.equal(Store.scaleFactor('DEV-A', 1), 2);
});

test('reset removes the record; a failed write reports false and changes nothing', () => {
  login({ uid: 7, role: 'customer_admin' });
  Store.saveScale('DEV-A', 1, { enabled: true, factor: 2 });
  assert.equal(Store.saveScale('DEV-A', 1, { ...S.DEFAULT_SCALE }), true);
  assert.equal(store.has('display-scale:u7:DEV-A#1'), false);
  Store.saveScale('DEV-A', 1, { enabled: true, factor: 2 });
  failWrites = true;
  assert.equal(Store.saveScale('DEV-A', 1, { enabled: true, factor: 5 }), false);
  assert.equal(Store.scaleFactor('DEV-A', 1), 2);
});

test('a corrupt stored record reads as default', () => {
  login({ uid: 7, role: 'customer_admin' });
  store.set('display-scale:u7:DEV-A#1', { enabled: true, factor: 'big' });
  assert.equal(Store.scaleFactor('DEV-A', 1), undefined);
});

console.log(`display-scale: ${n} checks passed`);
