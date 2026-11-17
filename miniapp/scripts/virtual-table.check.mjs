// Run with: npm run check   (node --experimental-strip-types)
// 虚拟罐表（Issue #56）：查表/插值/边界、派生读数口径、存储隔离（内存 wx 替身、伪造 JWT）。不是微信真机或 Donut 验收。
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
  getStorageInfoSync: () => ({ keys: [...store.keys()] }),
  base64ToArrayBuffer: (b64) => {
    const buf = Buffer.from(b64, 'base64');
    return buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength);
  },
};

const V = await import('../miniprogram/utils/virtualTable.ts');
const Store = await import('../miniprogram/utils/virtualTableStore.ts');
const { generateTankTable, previewRows } = await import('../miniprogram/utils/tankTable.ts');
const { toTankViews } = await import('../miniprogram/utils/telemetry.ts');

const jwt = (claims) => `h.${Buffer.from(JSON.stringify(claims)).toString('base64url')}.s`;
const login = (claims) => store.set('token', jwt(claims));

let n = 0;
const test = (name, fn) => {
  fn();
  n++;
  console.log(`ok - ${name}`);
};
const near = (a, b, eps = 1e-9) => assert.ok(Math.abs(a - b) <= eps, `${a} ≈ ${b}`);

// 截图的例子：卧罐 H=2600 L=5000 R=500 → 260 点，末点 2599 mm / 30086 L（不是 30000）
const now = new Date('2026-11-17T08:00:00Z');
const big = V.buildVirtualTable('horizontal', { H: 2600, L: 5000, R: 500 }, now);

test('the issue example builds the full 260-point table ending at 2599 mm / 30086 L', () => {
  assert.ok(big);
  assert.equal(big.heightMm.length, 260);
  assert.equal(big.heightMm.at(-1), 2599);
  assert.equal(big.volumeL.at(-1), 30086);
  assert.deepEqual(big.dims, { H: 2600, L: 5000, R: 500 });
  assert.equal(big.enabledAt, now.toISOString());
  const full = generateTankTable('horizontal', { H: 2600, L: 5000, R: 500 });
  assert.deepEqual(big.heightMm, full.heightMm);
  assert.deepEqual(big.volumeL, full.volumeL);
});

test('lookup: table points, linear between points, first segment from (0,0)', () => {
  const t = { heightMm: [10, 20, 40], volumeL: [5, 15, 55] };
  assert.deepEqual(V.lookupVolume(t, 20), { ok: true, volumeL: 15 });
  assert.deepEqual(V.lookupVolume(t, 40), { ok: true, volumeL: 55 });
  assert.deepEqual(V.lookupVolume(t, 30), { ok: true, volumeL: 35 });
  assert.deepEqual(V.lookupVolume(t, 15), { ok: true, volumeL: 10 });
  assert.deepEqual(V.lookupVolume(t, 0), { ok: true, volumeL: 0 });
  assert.deepEqual(V.lookupVolume(t, 5), { ok: true, volumeL: 2.5 });
  assert.deepEqual(V.lookupVolume(t, 10), { ok: true, volumeL: 5 });
});

test('lookup: missing, negative and above the last point are not readings (no extrapolation, no clamp, no 0)', () => {
  const t = { heightMm: [10, 20], volumeL: [5, 15] };
  for (const x of [undefined, null, NaN, Infinity, '12']) assert.deepEqual(V.lookupVolume(t, x), { ok: false, reason: 'missing' });
  assert.deepEqual(V.lookupVolume(t, -1), { ok: false, reason: 'range' });
  assert.deepEqual(V.lookupVolume(t, 20.0001), { ok: false, reason: 'range' });
  assert.deepEqual(V.lookupVolume(big, 2600), { ok: false, reason: 'range' });
});

test('lookup uses the full table, not the preview sample, at every point and midpoint', () => {
  const rows = previewRows(big);
  assert.ok(rows.length < big.heightMm.length || rows.length === big.heightMm.length);
  for (let i = 0; i < big.heightMm.length; i++) {
    assert.equal(V.lookupVolume(big, big.heightMm[i]).volumeL, big.volumeL[i]);
    if (i + 1 < big.heightMm.length) {
      const mid = (big.heightMm[i] + big.heightMm[i + 1]) / 2;
      near(V.lookupVolume(big, mid).volumeL, (big.volumeL[i] + big.volumeL[i + 1]) / 2, 1e-6);
    }
  }
});

test('a table cannot be replaced by a capacity ratio: curves of different shapes differ at the same level', () => {
  const vert = V.buildVirtualTable('vertical', { H: 2600, R: 2000 }, now);
  // 两张表按满罐容积等比拉伸后，中间液位仍不相等（卧罐是 S 形曲线、立罐是直线）
  const level = 650;
  const h = V.lookupVolume(big, level).volumeL / V.tableCapacity(big);
  const v = V.lookupVolume(vert, level).volumeL / V.tableCapacity(vert);
  assert.ok(Math.abs(h - v) > 0.05, `${h} vs ${v}`);
  // 卧罐中间液位约为半罐，四分之一高度远小于四分之一容积
  near(V.lookupVolume(big, 1300).volumeL, 15043, 0);
  assert.ok(V.lookupVolume(big, 650).volumeL < V.tableCapacity(big) / 4);
});

test('derived readings follow the device semantics, all from the virtual table', () => {
  // 设备：20000 L 的表上液位 1300 mm 读出 10000 L，密度 0.84 → 8400 kg，容量 20000 → 空容 10000
  const raw = { oilLevelMm: 1300, waterLevelMm: 100, oilVolumeL: 10000, oilWeightKg: 8400, nullVolumeL: 10000, waterVolumeL: 0 };
  const r = V.virtualReadings(raw, big);
  assert.equal(r.state, 'virtual');
  assert.equal(r.values.oilVolumeL, 15043);
  near(r.values.oilWeightKg, 15043 * 0.84, 1e-6);
  assert.equal(r.values.nullVolumeL, 30086 - 15043);
  assert.equal(r.values.waterVolumeL, V.lookupVolume(big, 100).volumeL);
  // 不是原容积 × 30086/20000：1/4 高度处，设备 20000 L 立罐表读 5000 L，等比换算得 7521.5，卧罐表查得的远小于它
  const q = V.virtualReadings({ oilLevelMm: 650, oilVolumeL: 5000, oilWeightKg: 4200 }, big);
  assert.ok(q.values.oilVolumeL < 5000 * (30086 / 20000) - 1000, String(q.values.oilVolumeL));
});

test('mass needs the device density: unknown when the device volume is not positive', () => {
  const r = V.virtualReadings({ oilLevelMm: 500, oilVolumeL: 0, oilWeightKg: 0 }, big);
  assert.equal(r.state, 'virtual');
  assert.equal(r.values.oilWeightKg, undefined);
  const r2 = V.virtualReadings({ oilLevelMm: 500 }, big);
  assert.equal(r2.values.oilWeightKg, undefined);
  const zero = V.virtualReadings({ oilLevelMm: 0, oilVolumeL: 0, oilWeightKg: 0 }, big);
  assert.equal(zero.values.oilVolumeL, 0);
  assert.equal(zero.values.oilWeightKg, 0);
  assert.equal(zero.values.nullVolumeL, 30086);
});

test('missing or out-of-range level falls back to the device values, all four, with the reason', () => {
  const raw = { oilVolumeL: 100, oilWeightKg: 84, nullVolumeL: 19900, waterVolumeL: 0 };
  const a = V.virtualReadings(raw, big);
  assert.deepEqual(a, { state: 'fallback', reason: 'missing', values: raw });
  const b = V.virtualReadings({ ...raw, oilLevelMm: 2650 }, big);
  assert.deepEqual(b, { state: 'fallback', reason: 'range', values: raw });
  // 水高缺失只让水体积保持设备值，其余照常按虚拟表
  const c = V.virtualReadings({ ...raw, oilLevelMm: 1300, waterVolumeL: 3 }, big);
  assert.equal(c.state, 'virtual');
  assert.equal(c.values.waterVolumeL, 3);
  // 未启用：设备原值，不缺不补
  assert.deepEqual(V.virtualReadings({ oilVolumeL: 1 }, null), { state: '', reason: '', values: { oilVolumeL: 1 } });
});

test('applyVirtual recomputes from raw every time: never converted twice, rounding only for display', () => {
  const base = { raw: { oilLevelMm: 1305, oilVolumeL: 10000.04, oilWeightKg: 8400 }, virtualState: '', virtualReason: '', oilVolumeL: 10000.04 };
  const once = V.applyVirtual(base, big);
  const twice = V.applyVirtual(once, big);
  assert.deepEqual(twice, once);
  assert.equal(once.virtualState, 'virtual');
  assert.equal(once.oilVolumeL, Math.round(((15043 + 15193.4) / 2) * 100) / 100);
  const back = V.applyVirtual(once, null);
  assert.equal(back.virtualState, '');
  assert.equal(back.oilVolumeL, 10000.04);
  assert.equal(back.nullVolumeL, undefined);
});

test('toTankViews: the fill and level stay the device ones, volume follows the table', () => {
  const res = { ts: '2026-11-17T08:00:00Z', tanks: [{ index: 1, oilLevelMm: 1300, oilVolumeL: 10000, oilWeightKg: 8400, nullVolumeL: 10000, waterVolumeL: 0, waterLevelMm: 0, tempC: -3.5, oilRatioPct: 50, waterRatioPct: 0, alarmState: 'NORMAL', probeConnected: true }] };
  const plain = toTankViews(res, 'D1')[0];
  const virt = toTankViews(res, 'D1', undefined, () => big)[0];
  assert.equal(plain.oilVolumeL, 10000);
  assert.equal(plain.virtualState, '');
  assert.equal(virt.oilVolumeL, 15043);
  assert.equal(virt.oilLevelMm, 1300);
  assert.equal(virt.tempC, -3.5);
  assert.equal(virt.fillOilPct, plain.fillOilPct);
  assert.equal(virt.raw.oilVolumeL, 10000);
});

test('tableUsable / readVirtual refuse broken or tampered tables', () => {
  assert.equal(V.tableUsable(null), false);
  assert.equal(V.tableUsable({ heightMm: [10], volumeL: [1] }), false);
  assert.equal(V.tableUsable({ heightMm: [10, 20], volumeL: [1] }), false);
  assert.equal(V.tableUsable({ heightMm: [10, 10], volumeL: [1, 2] }), false);
  assert.equal(V.tableUsable({ heightMm: [20, 10], volumeL: [1, 2] }), false);
  assert.equal(V.tableUsable({ heightMm: [10, 20], volumeL: [2, 1] }), false);
  assert.equal(V.tableUsable({ heightMm: [10, 20], volumeL: [1, NaN] }), false);
  assert.equal(V.tableUsable({ heightMm: [0, 20], volumeL: [0, 1] }), false);
  assert.equal(V.tableUsable({ heightMm: [10, 20], volumeL: [1, 2] }), true);
  assert.deepEqual(V.readVirtual(structuredClone(big)), big);
  for (const bad of ['', 1, { ...big, shape: 'sphere' }, { ...big, enabledAt: 1 }, { ...big, dims: { H: 1 } }, { ...big, volumeL: big.volumeL.slice(1) }]) {
    assert.equal(V.readVirtual(bad), null);
  }
});

test('legacy scale keys: only the current account, nothing converted', () => {
  assert.deepEqual(V.legacyScaleKeys(['display-scale:u1:D#1', 'display-scale:u2:D#1', 'token', 'display-scale:u10:D#1'], 'u1'), ['display-scale:u1:D#1']);
  assert.deepEqual(V.legacyScaleKeys(['display-scale:u1:D#1'], ''), []);
});

test('store: administrators only, keyed by account + full device + tank', () => {
  store.clear();
  login({ uid: 1, role: 'customer_admin' });
  assert.equal(Store.virtualAllowed('ZC-001', 1), true);
  assert.equal(Store.saveVirtual('ZC-001', 1, big), true);
  assert.ok(store.has('virtual-table:u1:ZC-001#1'));
  assert.deepEqual(Store.loadVirtual('ZC-001', 1), big);
  // 同设备另一罐、另一设备同罐号：没有
  assert.equal(Store.loadVirtual('ZC-001', 2), null);
  assert.equal(Store.loadVirtual('ZC-0011', 1), null);
  // 另一账号读不到、也不会应用
  login({ uid: 2, role: 'customer_admin' });
  assert.equal(Store.loadVirtual('ZC-001', 1), null);
  // 同一账号变成员工（重新登录后）：不应用，不能写
  login({ uid: 1, role: 'customer_user' });
  assert.equal(Store.virtualAllowed('ZC-001', 1), false);
  assert.equal(Store.loadVirtual('ZC-001', 1), null);
  assert.equal(Store.saveVirtual('ZC-001', 1, big), false);
  assert.equal(Store.removeVirtual('ZC-001', 1), false);
  // 没有 role 的 token 当员工
  login({ uid: 1 });
  assert.equal(Store.loadVirtual('ZC-001', 1), null);
  login({ uid: 1, role: 'platform_admin' });
  assert.deepEqual(Store.loadVirtual('ZC-001', 1), big);
});

test('store: write/remove failures are reported, a corrupt record reads as none, stop restores device values', () => {
  store.clear();
  login({ uid: 3, role: 'platform_admin' });
  failWrites = true;
  assert.equal(Store.saveVirtual('D', 1, big), false);
  assert.equal(Store.loadVirtual('D', 1), null);
  failWrites = false;
  assert.equal(Store.saveVirtual('D', 1, big), true);
  failWrites = true;
  assert.equal(Store.removeVirtual('D', 1), false);
  failWrites = false;
  assert.equal(Store.removeVirtual('D', 1), true);
  assert.equal(Store.loadVirtual('D', 1), null);
  store.set('virtual-table:u3:D#1', { shape: 'horizontal', heightMm: [1], volumeL: [1] });
  assert.equal(Store.loadVirtual('D', 1), null);
});

test('store: legacy display-scale settings are dropped once for the current account only', () => {
  store.clear();
  login({ uid: 5, role: 'customer_admin' });
  store.set('display-scale:u5:D#1', { enabled: true, factor: 1.5 });
  store.set('display-scale:u6:D#1', { enabled: true, factor: 2 });
  assert.equal(Store.dropLegacyScale(), true);
  assert.equal(store.has('display-scale:u5:D#1'), false);
  assert.equal(store.has('display-scale:u6:D#1'), true);
  assert.equal(Store.dropLegacyScale(), false);
  assert.equal(Store.loadVirtual('D', 1), null, 'a factor is not turned into a table');
});

console.log(`${n} checks passed`);
