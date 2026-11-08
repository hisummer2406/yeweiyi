// Run with: npm run check   (node --experimental-strip-types)
// Flow checks that need `wx`: the real request.ts / configSubmit.ts run against an in-memory wx stand-in
// (storage, wx.request, modals). Issue #18 F5 (late renewal) and F2 (unconfirmed submission kept).
// Not a substitute for DevTools / phone / real-cloud acceptance.
import assert from 'node:assert/strict';
import { register } from 'node:module';

// The mini-program imports without extensions ('./request'); resolve them to the .ts files.
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

// ---- wx stand-in ----
const store = new Map();
let pendingRequests = []; // { opts } waiting for an answer from the test
let modalAnswers = []; // true = confirm, false = cancel, consumed in order
const modals = [];
const navigations = [];
const posts = [];
let onRequest = null; // optional synchronous responder

globalThis.wx = {
  getStorageSync: (k) => (store.has(k) ? structuredClone(store.get(k)) : ''),
  setStorageSync: (k, v) => store.set(k, structuredClone(v)),
  removeStorageSync: (k) => store.delete(k),
  showLoading() {},
  hideLoading() {},
  showToast() {},
  showModal(o) {
    modals.push(o);
    const a = modalAnswers.length ? modalAnswers.shift() : false;
    setTimeout(() => o.success({ confirm: a, cancel: !a }), 0);
  },
  navigateTo: (o) => navigations.push(o.url),
  reLaunch: (o) => o.complete && o.complete(),
  getSystemInfoSync: () => ({ language: 'zh_CN' }),
  getAppBaseInfo: () => ({ language: 'zh_CN' }),
  base64ToArrayBuffer: (b64) => {
    const b = Buffer.from(b64, 'base64');
    return b.buffer.slice(b.byteOffset, b.byteOffset + b.length);
  },
  request(opts) {
    if (opts.method === 'POST') posts.push(opts);
    if (onRequest && onRequest(opts)) return;
    pendingRequests.push(opts);
  },
};

const { request } = await import('../miniprogram/utils/request.ts');
const { setToken, getToken } = await import('../miniprogram/utils/storage.ts');
const { confirmAndSend } = await import('../miniprogram/utils/configSubmit.ts');

let n = 0;
const test = async (name, fn) => {
  await fn();
  n++;
  console.log('ok -', name);
};
const tick = () => new Promise((r) => setTimeout(r, 5));
const reset = () => {
  store.clear();
  pendingRequests = [];
  modalAnswers = [];
  modals.length = 0;
  navigations.length = 0;
  posts.length = 0;
  onRequest = null;
};
// A JWT-shaped token with a uid claim (only the payload is read).
const jwt = (uid, tag = '') =>
  `h.${Buffer.from(JSON.stringify({ uid, tag })).toString('base64url')}.s`;

// ---- F5 ----
await test('F5: A request answering after a switch to B does not store A\'s renewal', async () => {
  reset();
  setToken('account-A');
  const p = request({ url: '/api/devices' });
  const sent = pendingRequests.shift();
  assert.equal(sent.header.Authorization, 'Bearer account-A');
  setToken('account-B');
  sent.success({ statusCode: 200, data: {}, header: { 'X-Renewed-Token': 'renewed-account-A' } });
  await p;
  assert.equal(getToken(), 'account-B');
});

await test('F5: a late renewal after logout does not sign back in', async () => {
  reset();
  setToken('account-A');
  const p = request({ url: '/api/devices' });
  const sent = pendingRequests.shift();
  store.delete('token');
  sent.success({ statusCode: 200, data: {}, header: { 'x-renewed-token': 'renewed-account-A' } });
  await p;
  assert.equal(getToken(), '');
});

await test('F5: same session renews; parallel answers keep one valid renewal', async () => {
  reset();
  setToken('T');
  const p1 = request({ url: '/a' });
  const p2 = request({ url: '/b' });
  const [r1, r2] = pendingRequests.splice(0);
  r1.success({ statusCode: 200, data: {}, header: { 'x-renewed-token': 'T1' } });
  r2.success({ statusCode: 200, data: {}, header: { 'x-renewed-token': 'T2' } });
  await Promise.all([p1, p2]);
  assert.equal(getToken(), 'T1');
  const p3 = request({ url: '/c' });
  const r3 = pendingRequests.shift();
  assert.equal(r3.header.Authorization, 'Bearer T1');
  r3.success({ statusCode: 200, data: {}, header: { 'x-renewed-token': 'T3' } });
  await p3;
  assert.equal(getToken(), 'T3');
});

// ---- F2 ----
const DEV = 'ZCT000001';
const devicesOk = { rows: [{ device_name: DEV, station_id: 7 }] };
// Responder: GET /api/devices ok; POST /control → `post`; GET ?requestId= → `lookup`.
function cloud({ post, lookup }) {
  onRequest = (o) => {
    if (o.method === 'GET' && o.url.endsWith('/api/devices')) {
      setTimeout(() => o.success({ statusCode: 200, data: devicesOk, header: {} }), 0);
      return true;
    }
    if (o.method === 'POST') {
      setTimeout(() => post(o), 0);
      return true;
    }
    if (o.method === 'GET' && o.data && o.data.requestId) {
      setTimeout(() => lookup(o), 0);
      return true;
    }
    return false;
  };
}
const timeout = (o) => o.fail({ errMsg: 'request:fail timeout' });
const notFound = (o) => o.success({ statusCode: 200, data: { commands: [] }, header: {} });
const accepted = (o) =>
  o.success({ statusCode: 201, data: { commandId: 'cmd-1', status: 'queued', requestId: o.data.requestId }, header: {} });

const form = (alarmHighLevelMm) => ({
  device: DEV,
  stationId: 7,
  tank: 1,
  args: { alarmHighLevelMm },
  reason: 'r',
  lines: [{ label: '高报警', before: '600', after: String(alarmHighLevelMm) }],
});

await test('F2: timeout → not found → 稍后查看 → submit again resends the SAME id and the ORIGINAL content', async () => {
  reset();
  setToken(jwt(12));
  cloud({ post: timeout, lookup: notFound });
  modalAnswers = [true /* confirm */, false /* 稍后查看 */];
  await confirmAndSend(form(700));
  assert.equal(posts.length, 1);
  const first = posts[0].data;

  // back later with a different form value; the kept submission comes first and is retried as it was
  cloud({ post: accepted, lookup: notFound });
  modalAnswers = [true /* 用同一编号重试 */];
  await confirmAndSend(form(900));
  await tick();
  assert.equal(posts.length, 2);
  assert.equal(posts[1].data.requestId, first.requestId);
  assert.deepEqual(posts[1].data.args, first.args);
  assert.equal(posts[1].data.args.alarmHighLevelMm, 700);
  assert.equal(posts[1].data.expectedStationId, 7);
  assert.ok(navigations.at(-1).includes('commandId=cmd-1'));

  // settled: the next submission is a fresh confirmation with a new id
  modalAnswers = [true];
  await confirmAndSend(form(900));
  await tick();
  assert.equal(posts.length, 3);
  assert.notEqual(posts[2].data.requestId, first.requestId);
  assert.equal(posts[2].data.args.alarmHighLevelMm, 900);
});

await test('F2: the cloud had taken it — found by requestId on return, nothing is sent again', async () => {
  reset();
  setToken(jwt(12));
  cloud({ post: timeout, lookup: notFound });
  modalAnswers = [true, false];
  await confirmAndSend(form(700));
  const id = posts[0].data.requestId;
  cloud({
    post: accepted,
    lookup: (o) =>
      o.success({ statusCode: 200, data: { commands: [{ commandId: 'cmd-9', requestId: id, status: 'succeeded' }] }, header: {} }),
  });
  await confirmAndSend(form(900));
  await tick();
  assert.equal(posts.length, 1);
  assert.ok(navigations.at(-1).includes('commandId=cmd-9'));
});

await test('F2: 稍后查看 again keeps it; another account does not see it', async () => {
  reset();
  setToken(jwt(12));
  cloud({ post: timeout, lookup: notFound });
  modalAnswers = [true, false];
  await confirmAndSend(form(700));
  modalAnswers = [false /* 稍后查看 */];
  await confirmAndSend(form(900));
  assert.equal(posts.length, 1); // nothing sent
  // account B: its own fresh confirmation, new id
  setToken(jwt(34));
  cloud({ post: accepted, lookup: notFound });
  modalAnswers = [true];
  await confirmAndSend(form(900));
  await tick();
  assert.equal(posts.length, 2);
  assert.notEqual(posts[1].data.requestId, posts[0].data.requestId);
  // back to A: still kept
  setToken(jwt(12));
  modalAnswers = [true];
  await confirmAndSend(form(900));
  await tick();
  assert.equal(posts.length, 3);
  assert.equal(posts[2].data.requestId, posts[0].data.requestId);
});

await test('F2: another tank or device is not blocked; a definitive rejection settles it', async () => {
  reset();
  setToken(jwt(12));
  cloud({ post: timeout, lookup: notFound });
  modalAnswers = [true, false];
  await confirmAndSend(form(700));
  cloud({ post: accepted, lookup: notFound });
  modalAnswers = [true];
  await confirmAndSend({ ...form(800), tank: 2 });
  await tick();
  assert.equal(posts.length, 2);
  assert.notEqual(posts[1].data.requestId, posts[0].data.requestId);
  // retry of tank 1 answered device_station_changed: settled, the next one is fresh
  cloud({
    post: (o) =>
      o.success({ statusCode: 409, data: { error: 'x', code: 'device_station_changed', currentStationId: 8 }, header: {} }),
    lookup: notFound,
  });
  modalAnswers = [true /* retry */, true /* 知道了 */];
  await confirmAndSend(form(900));
  await tick();
  assert.equal(posts.length, 3);
  assert.equal(posts[2].data.requestId, posts[0].data.requestId);
  cloud({ post: accepted, lookup: notFound });
  modalAnswers = [true];
  await confirmAndSend(form(900));
  await tick();
  assert.equal(posts.length, 4);
  assert.notEqual(posts[3].data.requestId, posts[0].data.requestId);
});

// ---- F4 ----
await test('F4: a double tap runs one flow and one requestId; after it ends a new one can start', async () => {
  reset();
  setToken(jwt(12));
  cloud({ post: accepted, lookup: notFound });
  modalAnswers = [true, true];
  await Promise.all([confirmAndSend(form(700)), confirmAndSend(form(700))]);
  await tick();
  assert.equal(modals.length, 1);
  assert.equal(posts.length, 1);
  modalAnswers = [true];
  await confirmAndSend(form(800));
  await tick();
  assert.equal(posts.length, 2);
  assert.notEqual(posts[1].data.requestId, posts[0].data.requestId);
});

await test('F4: a tap during the unconfirmed dialog is ignored; the same-id retry inside the flow still works', async () => {
  reset();
  setToken(jwt(12));
  cloud({ post: timeout, lookup: notFound });
  modalAnswers = [true /* confirm */];
  // the "unconfirmed" dialog answers only when told to
  let answerRetry;
  const origShow = wx.showModal;
  let shown = 0;
  wx.showModal = (o) => {
    shown++;
    modals.push(o);
    if (shown === 1) return setTimeout(() => o.success({ confirm: true }), 0);
    answerRetry = () => o.success({ confirm: true });
  };
  const flow = confirmAndSend(form(700));
  while (!answerRetry) await tick();
  await confirmAndSend(form(900)); // second tap while the dialog is open: ignored at once
  assert.equal(posts.length, 1);
  cloud({ post: accepted, lookup: notFound });
  answerRetry();
  await flow;
  await tick();
  wx.showModal = origShow;
  assert.equal(posts.length, 2);
  assert.equal(posts[1].data.requestId, posts[0].data.requestId);
  assert.equal(posts[1].data.args.alarmHighLevelMm, 700);
});

console.log(`${n} checks passed`);
