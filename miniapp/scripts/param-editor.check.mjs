// Run with: npm run check   (node --experimental-strip-types)
// Flow checks of components/param-editor (Issue #43): the real component code runs against an in-memory `wx`
// and a minimal Component() host (data/setData/observers). Not DevTools, phone or Donut acceptance.
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

// ---- wx stand-in ----
const store = new Map();
let modalAnswers = [];
const modals = [];
const navigations = [];
const posts = [];
let held = []; // requests held back by the test
let route = () => false;

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
    if (route(opts)) return;
    held.push(opts);
  },
};

let def;
globalThis.Component = (d) => {
  def = d;
};
const { setToken } = await import('../miniprogram/utils/storage.ts');
const { formatLocalTime } = await import('../miniprogram/utils/format.ts');
await import('../miniprogram/components/param-editor/param-editor.ts');

// A minimal host: property defaults + data, setData merges, the object observer runs on property changes.
function mount(props) {
  const data = {};
  for (const [k, v] of Object.entries(def.properties)) data[k] = v.value;
  Object.assign(data, structuredClone(def.data));
  const inst = {
    data,
    setData(patch) {
      Object.assign(this.data, patch);
    },
  };
  for (const [k, fn] of Object.entries(def.methods)) inst[k] = fn.bind(inst);
  def.lifetimes.created.call(inst);
  inst.props = (p) => {
    Object.assign(inst.data, p);
    def.observers['kind, device, tank, active'].call(inst);
  };
  inst.setStation = (station) => {
    inst.data.station = station;
    def.observers.station.call(inst);
  };
  inst.props(props);
  return inst;
}

let n = 0;
const test = async (name, fn) => {
  await fn();
  n++;
  console.log('ok -', name);
};
const tick = (ms = 5) => new Promise((r) => setTimeout(r, ms));
const jwt = (uid, role = 'customer_admin') => `h.${Buffer.from(JSON.stringify({ uid, role })).toString('base64url')}.s`;

const DEV = 'ZCT000001';
let mirrors;
let command;
let postReply = null;
let mediaOptions = null;
const ok = (o, data) => setTimeout(() => o.success({ statusCode: 200, data, header: {} }), 0);
function cloud({ hold = false } = {}) {
  route = (o) => {
    if (o.url.endsWith('/tank-settings')) {
      if (hold) return false;
      ok(o, { deviceName: DEV, tanks: structuredClone(mirrors) });
      return true;
    }
    if (o.url.endsWith('/media-options')) {
      if (!mediaOptions) {
        setTimeout(() => o.success({ statusCode: 403, data: { error: 'not authorized for this device' }, header: {} }), 0);
        return true;
      }
      ok(o, structuredClone(mediaOptions));
      return true;
    }
    if (o.method === 'GET' && o.url.endsWith('/api/devices')) {
      ok(o, { rows: [{ device_name: DEV, station_id: 7 }] });
      return true;
    }
    if (o.method === 'POST') {
      const data = postReply ? structuredClone(postReply) : { commandId: 'cmd-1', status: 'pending', terminal: false, args: o.data.args };
      setTimeout(() => o.success({ statusCode: 202, data, header: {} }), 0);
      return true;
    }
    if (o.method === 'GET' && /\/commands\/cmd-1$/.test(o.url)) {
      ok(o, structuredClone(command));
      return true;
    }
    return false;
  };
}
const reset = () => {
  store.clear();
  modalAnswers = [];
  modals.length = 0;
  navigations.length = 0;
  posts.length = 0;
  held = [];
  mirrors = [
    { tank: 1, reportedAt: '2026-11-14T01:00:00Z', levelCorrectionMm: 0, waterCorrectionMm: 0, tempCorrectionC: 0 },
    { tank: 3, reportedAt: '2026-11-14T02:00:00Z', levelCorrectionMm: 2, waterCorrectionMm: null, tempCorrectionC: -0.5 },
  ];
  command = { commandId: 'cmd-1', status: 'pending', terminal: false };
  postReply = null;
  mediaOptions = {
    public: [{ id: 5, name: '0#柴油', builtinIndex: 5 }],
    customer: [{ id: 90, name: '盐酸', builtinIndex: null }],
    customMediaSync: true,
    oilColorSync: true,
    slotsUsed: 0,
    slotsTotal: 8,
  };
  setToken(jwt(12));
  cloud();
};
const values = (ed) => ed.data.rows.map((r) => r.value);
const props = { kind: 'calibration', device: DEV, tank: 3, station: 7, active: true };

await test('opens on the given tank with the device report filled in; missing stays empty', async () => {
  reset();
  const ed = mount(props);
  await tick();
  assert.equal(ed.data.state, 'ok');
  assert.deepEqual(values(ed), ['2', '', '-0.5']);
  // the mirror's own report time, not the time the page was opened
  assert.ok(ed.data.reportedText.includes(formatLocalTime('2026-11-14T02:00:00Z', 'zh')), ed.data.reportedText);
  assert.equal(ed.data.changedCount, 0);
  assert.equal(ed.data.canSend, true);
});

await test('not active: nothing is read', async () => {
  reset();
  const ed = mount({ ...props, active: false });
  await tick();
  assert.equal(ed.data.state, 'idle');
});

await test('an edit is auto-saved per account/device/tank; a refresh keeps it and flags a moved report', async () => {
  reset();
  const ed = mount(props);
  await tick();
  ed.setInput('levelOffset', '-1.5');
  assert.equal(ed.data.changedCount, 1);
  assert.equal([...store.keys()].filter((k) => k.startsWith('param-draft:calibration:u12:')).length, 1);
  mirrors[1] = { ...mirrors[1], reportedAt: '2026-11-14T03:00:00Z', levelCorrectionMm: 3, waterCorrectionMm: 4 };
  ed.refresh();
  await tick();
  assert.deepEqual(values(ed), ['-1.5', '4', '-0.5']);
  assert.equal(ed.data.moved, true);
});

await test('send: only the changed field, stays on the page, shows the device refusal reason; draft kept', async () => {
  reset();
  const ed = mount(props);
  await tick();
  ed.setInput('levelOffset', '-1.5');
  ed.onReasonInput({ detail: { value: '现场校准' } });
  modalAnswers = [true];
  command = { commandId: 'cmd-1', status: 'rejected', terminal: true, errorCode: 'INVALID_PARAMS', errorReason: '修正值超出范围' };
  ed.onSend();
  await tick(30);
  assert.equal(posts.length, 1);
  assert.deepEqual(posts[0].data.args, { tank: 3, levelCorrectionMm: -1.5 });
  assert.equal(posts[0].data.expectedStationId, 7);
  assert.match(modals[0].content, /2 毫米 → -1\.5 毫米/);
  assert.match(modals[0].content, /现场校准/);
  assert.deepEqual(navigations, []);
  assert.equal(ed.data.result.tone, 'bad');
  assert.equal(ed.data.result.error, '修正值超出范围');
  assert.equal(ed.data.changedCount, 1);
  ed.stopTrack();
});

await test('success: waits for a fresh report; once it matches, the edit is gone and the draft removed', async () => {
  reset();
  const ed = mount(props);
  await tick();
  ed.setInput('levelOffset', '-1.5');
  ed.onReasonInput({ detail: { value: 'r' } });
  modalAnswers = [true];
  command = { commandId: 'cmd-1', status: 'succeeded', terminal: true, args: { tank: 3, levelCorrectionMm: -1.5 }, finishedAt: '2026-11-14T04:00:00Z' };
  ed.onSend();
  await tick(30);
  // the mirror is still the old report (earlier than finishedAt): not yet effective
  assert.equal(ed.data.result.tone, 'info');
  assert.equal(ed.data.changedCount, 1);
  mirrors[1] = { ...mirrors[1], reportedAt: '2026-11-14T04:00:05Z', levelCorrectionMm: -1.5 };
  ed.stopTrack();
  ed.resumeTrack();
  await tick(30);
  assert.equal(ed.data.result.tone, 'ok');
  assert.equal(ed.data.changedCount, 0);
  assert.deepEqual(values(ed), ['-1.5', '', '-0.5']);
  assert.equal([...store.keys()].some((k) => k.startsWith('param-draft:')), false);
  ed.stopTrack();
});

await test('cancelling the confirmation sends nothing', async () => {
  reset();
  const ed = mount(props);
  await tick();
  ed.setInput('tempOffset', '1');
  ed.onReasonInput({ detail: { value: 'r' } });
  modalAnswers = [false];
  ed.onSend();
  await tick(20);
  assert.equal(posts.length, 0);
});

await test('switching tank: the result stays with its tank; the draft comes back only on its own tank and account', async () => {
  reset();
  const ed = mount(props);
  await tick();
  ed.setInput('levelOffset', '-1.5');
  ed.onReasonInput({ detail: { value: 'r' } });
  modalAnswers = [true];
  ed.onSend();
  await tick(30);
  assert.ok(ed.data.result);
  ed.props({ tank: 1 });
  await tick();
  assert.equal(ed.data.result, null);
  assert.deepEqual(values(ed), ['0', '0', '0']);
  assert.equal(ed.data.draftNote, '');
  ed.props({ tank: 3 });
  await tick();
  assert.deepEqual(values(ed), ['-1.5', '', '-0.5']);
  assert.notEqual(ed.data.draftNote, '');
  assert.ok(ed.data.result, 'the result of tank 3 shows again on tank 3');
  ed.stopTrack();
  setToken(jwt(13));
  const other = mount(props);
  await tick();
  assert.deepEqual(values(other), ['2', '', '-0.5']);
});

await test('a late report for the previous tank is dropped', async () => {
  reset();
  cloud({ hold: true });
  const ed = mount(props);
  const late = held.shift();
  ed.props({ tank: 1 });
  const now = held.shift();
  ok(now, { deviceName: DEV, tanks: mirrors });
  await tick();
  ok(late, { deviceName: DEV, tanks: [{ ...mirrors[1], tank: 1, levelCorrectionMm: 99 }] });
  await tick();
  assert.deepEqual(values(ed), ['0', '0', '0']);
});

await test('read failure: error state, nothing sendable', async () => {
  reset();
  route = (o) => {
    if (o.url.endsWith('/tank-settings')) {
      setTimeout(() => o.success({ statusCode: 500, data: { error: 'x' }, header: {} }), 0);
      return true;
    }
    return false;
  };
  const ed = mount(props);
  await tick();
  assert.equal(ed.data.state, 'error');
  assert.equal(ed.data.canSend, false);
});

await test('station not known yet: no send', async () => {
  reset();
  const ed = mount({ ...props, station: -2 });
  await tick();
  assert.equal(ed.data.canSend, false);
  assert.notEqual(ed.data.sendBlock, '');
});

await test('station found after an edit: send becomes available, the edit kept', async () => {
  reset();
  const ed = mount({ ...props, station: -2 });
  await tick();
  ed.setInput('levelOffset', '-1.5');
  assert.equal(ed.data.canSend, false);
  ed.setStation(7);
  assert.equal(ed.data.canSend, true);
  assert.equal(ed.data.sendBlock, '');
  assert.equal(ed.data.changedCount, 1);
});

await test('an already-succeeded command (recovered/retried) still waits for a fresh report', async () => {
  reset();
  const ed = mount(props);
  await tick();
  ed.setInput('levelOffset', '-1.5');
  ed.onReasonInput({ detail: { value: 'r' } });
  modalAnswers = [true];
  postReply = { commandId: 'cmd-1', status: 'succeeded', terminal: true, args: { tank: 3, levelCorrectionMm: -1.5 }, finishedAt: '2026-11-14T04:00:00Z' };
  mirrors[1] = { ...mirrors[1], reportedAt: '2026-11-14T04:00:05Z', levelCorrectionMm: -1.5 };
  ed.onSend();
  await tick(30);
  assert.equal(ed.data.result.tone, 'ok');
  assert.equal(ed.data.changedCount, 0);
  ed.stopTrack();
});

await test('a recovered tank-table command goes to 操作记录, never compared with the settings mirror', async () => {
  reset();
  const ed = mount(props);
  await tick();
  ed.setInput('levelOffset', '-1.5');
  ed.onReasonInput({ detail: { value: 'r' } });
  modalAnswers = [true];
  postReply = { commandId: 'cmd-9', method: 'config.setTankTable', status: 'succeeded', terminal: true, args: { tank: 3, shape: 0 }, finishedAt: '2026-11-14T04:00:00Z' };
  ed.onSend();
  await tick(30);
  assert.equal(ed.data.result, null);
  assert.equal(navigations.length, 1);
  assert.match(navigations[0], /issue-command\?device=ZCT000001&commandId=cmd-9&tank=3/);
  assert.equal(ed.data.changedCount, 1);
});

await test('an in-flight holder known only by id: handed off once its method is read', async () => {
  reset();
  const ed = mount(props);
  await tick();
  command = { commandId: 'cmd-1', method: 'config.setTankTable', status: 'pending', terminal: false };
  ed.track({ device: DEV, tank: 3, cmd: { commandId: 'cmd-1' }, echoStarted: 0 });
  await tick(30);
  assert.equal(ed.data.result, null);
  assert.equal(navigations.length, 1);
  assert.match(navigations[0], /commandId=cmd-1&tank=3/);
});

await test('employee: read only, typing stores nothing', async () => {
  reset();
  setToken(jwt(20, 'customer_user'));
  const ed = mount(props);
  await tick();
  assert.equal(ed.data.readOnly, true);
  assert.equal(ed.data.canSend, false);
  ed.setInput('levelOffset', '5');
  assert.equal([...store.keys()].some((k) => k.startsWith('param-draft:')), false);
});

await test('tank kind: eight sendable fields incl. density (g/cm³), height/low temp read only', async () => {
  reset();
  mirrors[1] = { ...mirrors[1], capacityL: 30000, alarmHighLevelMm: 1800, density: 0.84, tankHeightMm: 2500, alarmLowTempC: null };
  const ed = mount({ ...props, kind: 'tank' });
  await tick();
  assert.deepEqual(ed.data.rows.map((r) => r.key), ['capacity', 'highHigh', 'high', 'lowLow', 'low', 'waterHigh', 'tempHigh', 'density']);
  assert.equal(ed.data.rows[0].value, '30000');
  assert.equal(ed.data.rows[7].unit, 'g/cm³');
  assert.deepEqual(ed.data.roRows.map((r) => [r.key, r.value]), [['height', '2500'], ['tempLow', '未上报']]);
});

await test('tank kind, admin: choose medium and colour; both are sent with the other changes', async () => {
  reset();
  mirrors[1] = { ...mirrors[1], oilTypeIndex: 3, oilType: '92#', oilColor: 0x0000ff, density: 0.84 };
  const ed = mount({ ...props, kind: 'tank' });
  await tick(20);
  assert.equal(ed.data.mediumEditable, true);
  assert.equal(ed.data.mediumNote, '');
  assert.equal(ed.data.colorEditable, true);
  assert.equal(ed.data.colorNow, '#0000FF');
  assert.deepEqual(ed.data.mediumNames, ['0#柴油', '盐酸']);
  ed.onMediumPick({ detail: { value: '1' } });
  ed.onColorPick({ currentTarget: { dataset: { color: 0xff0000 } } });
  assert.equal(ed.data.changedCount, 2);
  ed.onReasonInput({ detail: { value: '换介质' } });
  modalAnswers = [true];
  ed.onSend();
  await tick(30);
  assert.equal(posts.length, 1);
  assert.deepEqual(posts[0].data.args, { tank: 3, mediaId: 90, oilColor: 0xff0000 });
  assert.match(modals[0].content, /盐酸/);
  ed.stopTrack();
});

await test('tank kind: colour read only without device support; a non-palette colour is marked', async () => {
  reset();
  mediaOptions.oilColorSync = false;
  mirrors[1] = { ...mirrors[1], oilColor: 0x123456 };
  const ed = mount({ ...props, kind: 'tank' });
  await tick(20);
  assert.equal(ed.data.mediumEditable, true);
  assert.equal(ed.data.colorEditable, false);
  assert.equal(ed.data.colorNote, '设备软件版本较旧，暂不支持远程修改颜色，请升级设备软件');
  assert.equal(ed.data.colorNonStandard, true);
});

await test('firmware without name sync: only built-in media below 18 can be chosen (the cloud refuses the rest)', async () => {
  reset();
  mediaOptions.customMediaSync = false;
  mediaOptions.public.push({ id: 20, name: '新内置', builtinIndex: 20 });
  const ed = mount({ ...props, kind: 'tank' });
  await tick(20);
  assert.deepEqual(ed.data.mediumNames, ['0#柴油']);
  assert.equal(ed.data.mediumNote, '设备软件版本较旧，自定义介质暂不可选，请升级设备软件');
  ed.onMediumPick({ detail: { value: '0' } });
  assert.deepEqual(ed.choiceArgs(), { mediaId: 5 });
});

await test('tank kind: options unavailable keeps medium read only, other fields still editable', async () => {
  reset();
  mediaOptions = null;
  const ed = mount({ ...props, kind: 'tank' });
  await tick(20);
  assert.equal(ed.data.mediumEditable, false);
  assert.equal(ed.data.colorEditable, false);
  assert.equal(ed.data.readOnly, false);
});

await test('tank kind, employee: medium, density and colour read only, options never asked', async () => {
  reset();
  setToken(jwt(20, 'customer_user'));
  const ed = mount({ ...props, kind: 'tank' });
  await tick(20);
  assert.equal(ed.data.readOnly, true);
  assert.equal(ed.data.mediumEditable, false);
  assert.equal(ed.data.colorEditable, false);
  assert.equal(held.some((o) => o.url.endsWith('/media-options')), false);
});

await test('a density that looks like kg/m³ is flagged for checking', async () => {
  reset();
  mirrors[1] = { ...mirrors[1], density: 840 };
  const ed = mount({ ...props, kind: 'tank' });
  await tick(20);
  const row = ed.data.rows.find((r) => r.key === 'density');
  assert.equal(row.warn, true);
  assert.match(row.note, /kg\/m³/);
});

await test('an odd density report (840) does not block sending another field', async () => {
  reset();
  mirrors[1] = { ...mirrors[1], density: 840, alarmHighLevelMm: 1800 };
  const ed = mount({ ...props, kind: 'tank' });
  await tick(20);
  ed.setInput('high', '1700');
  ed.onReasonInput({ detail: { value: 'r' } });
  modalAnswers = [true];
  ed.onSend();
  await tick(30);
  assert.equal(posts.length, 1);
  assert.deepEqual(posts[0].data.args, { tank: 3, alarmHighLevelMm: 1700 });
  ed.stopTrack();
});

await test('a succeeded command that the device partly ignored says so', async () => {
  reset();
  const ed = mount({ ...props, kind: 'tank' });
  await tick(20);
  ed.setInput('capacity', '100');
  ed.onReasonInput({ detail: { value: 'r' } });
  modalAnswers = [true];
  command = { commandId: 'cmd-1', status: 'succeeded', terminal: true, finishedAt: '2026-11-14T03:00:00Z', resultDetail: { ignored: ['oilColor'] } };
  ed.onSend();
  await tick(30);
  assert.match(ed.data.result.error, /oilColor/);
  ed.stopTrack();
});

console.log(`${n} param-editor flow checks passed`);
