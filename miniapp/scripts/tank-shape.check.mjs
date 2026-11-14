// Runs real page loading methods with an in-memory wx transport. No production requests.
import assert from 'node:assert/strict';
import { register } from 'node:module';
register('data:text/javascript,' + encodeURIComponent(`
export async function resolve(spec, ctx, next) {
 try { return await next(spec, ctx); }
 catch (e) {
  if ((spec.startsWith('./') || spec.startsWith('../')) && !spec.endsWith('.ts')) return next(spec + '.ts', ctx);
  throw e;
 }
}`));
const storage = new Map();
let pending = [];
globalThis.wx = {
 getStorageSync: (k) => storage.get(k) || '',
 setStorageSync: (k, v) => storage.set(k, v),
 removeStorageSync: (k) => storage.delete(k),
 getSystemInfoSync: () => ({ language: 'zh_CN' }),
 getAppBaseInfo: () => ({ language: 'zh_CN' }),
 base64ToArrayBuffer: (s) => { const b = Buffer.from(s, 'base64'); return b.buffer.slice(b.byteOffset, b.byteOffset+b.length); },
 request: (o) => pending.push(o),
 showToast() {}, reLaunch(o) { o.complete?.(); },
 createSelectorQuery() { return { select() { return this; }, boundingClientRect() { return this; }, exec(fn) { fn([]); } }; },
};
const jwt = (uid) => 'h.' + Buffer.from(JSON.stringify({uid, role:'customer_admin'})).toString('base64url') + '.s';
storage.set('token', jwt(1));
let definition;
globalThis.Page = (d) => { definition = d; };
await import('../miniprogram/pages/devices/devices.ts');
const homeDefinition = definition;
await import('../miniprogram/pages/device-detail/device-detail.ts');
const detailDefinition = definition;
const { emptyTankShapes, tankShapeOf } = await import('../miniprogram/utils/tankShape.ts');
const { toTankViews } = await import('../miniprogram/utils/telemetry.ts');
const snapshot = { ts:'2026-01-01T00:00:00Z', snapshotSeq:1, tanks:[{
 index:3, oilType:'demo', oilLevelMm:100, oilVolumeL:500, waterLevelMm:0, tempC:20, oilWeightKg:400,
 oilRatioPct:50, waterRatioPct:0, alarmState:'NORMAL', probeConnected:true,
}] };
const rows = (tankShape) => [{ tank:3, tankShape, reportedAt:'2026-01-01T00:00:00Z' }];
function instance(d) {
 const p = { ...d, data: structuredClone(d.data), shapeRequests:{}, shapeState:emptyTankShapes() };
 p.setData = (patch, callback) => {
  for (const [key, value] of Object.entries(patch)) {
   const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.');
   let target=p.data;
   for (const part of parts.slice(0,-1)) target=target[part];
   target[parts.at(-1)] = value;
  }
  callback?.();
 };
 return p;
}
function home() {
 const p=instance(homeDefinition);
 p.activeAccount='u1'; p.loadSeq=1;
 p.data.groups=[{ deviceName:'D', tanks:[], shapes:emptyTankShapes(), fetchedAt:'' }];
 return p;
}
function detail() { const p=instance(detailDefinition); p.data.device='D'; p.allTanks=[]; return p; }
function take(suffix) {
 const i=pending.findIndex((o) => o.url.endsWith(suffix));
 assert.notEqual(i,-1, suffix);
 return pending.splice(i,1)[0];
}
function answer(o, data, statusCode=200) { o.success({statusCode, data, header:{}}); }
const tick = () => new Promise((r) => setImmediate(r));
let count=0;
async function test(name, fn) { pending=[]; storage.set('token',jwt(1)); await fn(); count++; console.log('ok -',name); }

await test('strict type mapping and sparse tank identity', async () => {
 for(const [value, expected] of [[0,'horizontal'],[1,'vertical'],[null,'horizontal'],[2,'horizontal']]) {
  const state={rows:rows(value),status:'ok'};
  assert.equal(tankShapeOf(state,3).shape,expected);
  assert.equal(tankShapeOf(state,1).shape,'horizontal');
 }
});
await test('a tank without a reported shape is shown as horizontal, never as unknown', async () => {
 const views=toTankViews(snapshot,'D');
 assert.equal(views[0].shape,'horizontal');
 assert.equal(views[0].shapeImage,'/assets/illustrations/tank-horizontal-home.png');
 assert.equal(views[0].shapeLabel,'卧罐');
});
await test('home readings render before config, then merge by real tank number', async () => {
 const p=home(); const loading=p.loadDevice('D',1);
 answer(take('/telemetry/latest'),snapshot); await tick();
 assert.equal(p.data.groups[0].tanks[0].oilLevelMm,100);
 assert.equal(p.data.groups[0].tanks[0].shape,'horizontal');
 answer(take('/tank-settings'),{tanks:rows(1)}); await loading;
 assert.equal(p.data.groups[0].tanks[0].shape,'vertical');
 assert.equal(p.data.groups[0].tanks.length,1);
});
await test('home rejects an older shape response and a hidden-page response', async () => {
 const p=home(); const old=p.loadShapes('D',1); const oldReq=take('/tank-settings');
 const recent=p.loadShapes('D',1); answer(take('/tank-settings'),{tanks:rows(0)}); await recent;
 answer(oldReq,{tanks:rows(1)}); await old;
 assert.equal(p.data.groups[0].shapes.rows[0].tankShape,0);
 const hidden=p.loadShapes('D',1); p.onHide(); answer(take('/tank-settings'),{tanks:rows(1)}); await hidden;
 assert.equal(p.data.groups[0].shapes.rows[0].tankShape,0);
});
await test('home keeps old shape only on transient failure, not 403', async () => {
 const p=home(); p.data.groups[0].shapes={rows:rows(1),status:'ok'};
 p.data.groups[0].tanks=toTankViews(snapshot,'D',p.data.groups[0].shapes);
 let load=p.loadShapes('D',1); answer(take('/tank-settings'),{},503); await load;
 assert.equal(p.data.groups[0].tanks[0].shape,'vertical');
 load=p.loadShapes('D',1); answer(take('/tank-settings'),{},403); await load;
 assert.equal(p.data.groups[0].tanks[0].shape,'horizontal');
 assert.equal(p.data.groups[0].shapes.rows.length,0);
});
await test('detail receives readings independently, then shape and its own reportedAt', async () => {
 const p=detail(); const load=p.loadSnapshot();
 answer(take('/telemetry/latest'),snapshot); await load;
 assert.equal(p.data.tanks[0].shapeTime,'');
 answer(take('/tank-settings'),{tanks:rows(0)}); await tick();
 assert.equal(p.data.tanks[0].shape,'horizontal');
 assert.match(p.data.tanks[0].shapeTime,/罐型上报时间/);
});
await test('detail discards shape results after switch, unload or account change', async () => {
 for(const change of [(p)=>{p.data.device='NEW';}, (p)=>{p.unloaded=true;}, ()=>storage.set('token',jwt(2))]) {
  storage.set('token',jwt(1)); const p=detail(); const load=p.loadShapes();
  change(p); answer(take('/tank-settings'),{tanks:rows(1)}); await load;
  assert.equal(p.shapeState.rows.length,0);
 }
});
await test('home account switch cannot adopt an earlier account response', async () => {
 const p=home(); const load=p.loadShapes('D',1); storage.set('token',jwt(2));
 answer(take('/tank-settings'),{tanks:rows(1)}); await load;
 assert.equal(p.data.groups[0].shapes.rows.length,0);
});
console.log(count+' tank-shape flow checks passed');
