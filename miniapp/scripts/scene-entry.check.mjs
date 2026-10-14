// Run with: npm run check   (node --experimental-strip-types; pure logic only, no wx)
import assert from 'node:assert/strict';
import { classifyEntry, detailUrlForScene, loginUrlForScene, loginUrlForSession, sceneFromQuery, setActiveScene } from '../miniprogram/utils/sceneEntry.ts';

let n = 0;
const test = (name, fn) => {
  fn();
  n++;
  console.log('ok -', name);
};

test('scene is the device name verbatim; decoded once', () => {
  assert.equal(sceneFromQuery({ scene: 'ZCT000001' }), 'ZCT000001');
  assert.equal(sceneFromQuery({ scene: '000001' }), '000001');
  assert.equal(sceneFromQuery({ scene: 'AB-12_x' }), 'AB-12_x');
  assert.equal(sceneFromQuery({ scene: encodeURIComponent('ZCT000001') }), 'ZCT000001');
});

test('missing, empty, malformed or non-code scene is no scene', () => {
  for (const q of [undefined, {}, { scene: '' }, { scene: '%E0%A4%A' }, { scene: 'a b' }, { scene: 'https://x/y' }, { scene: 'a'.repeat(65) }, { device: 'ZCT000001' }]) {
    assert.equal(sceneFromQuery(q), null, JSON.stringify(q));
  }
});

test('login round trip keeps the scene', () => {
  const login = loginUrlForScene('ZCT000001');
  assert.equal(login, '/pages/login/login?scene=ZCT000001');
  assert.equal(detailUrlForScene('ZCT000001'), '/pages/device-detail/device-detail?scene=ZCT000001');
  const back = new URL('http://x' + login).searchParams.get('scene');
  assert.equal(sceneFromQuery({ scene: back }), 'ZCT000001');
});

test('a session that dies while a scene page is open returns there; otherwise plain login', () => {
  assert.equal(loginUrlForSession(), '/pages/login/login');
  setActiveScene('ZCT000001');
  assert.equal(loginUrlForSession(), '/pages/login/login?scene=ZCT000001');
  setActiveScene(null);
  assert.equal(loginUrlForSession(), '/pages/login/login');
});

test('entry kind: scene, station, plain device, invalid', () => {
  assert.deepEqual(classifyEntry({ scene: 'ZCT000001' }), { kind: 'scene', scene: 'ZCT000001' });
  assert.deepEqual(classifyEntry({ device: 'ZCT000001', station: '12' }), { kind: 'station', device: 'ZCT000001', stationId: 12 });
  assert.deepEqual(classifyEntry({ device: 'ZCT000001' }), { kind: 'device', device: 'ZCT000001' });
  assert.deepEqual(classifyEntry({ device: 'ZCT000001', station: 'none' }), { kind: 'device', device: 'ZCT000001' });
});

test('no device and no usable scene is invalid, never an empty device', () => {
  for (const q of [undefined, {}, { device: '' }, { scene: '' }, { scene: 'a b' }, { scene: '%E0%A4%A' }, { station: '12' }]) {
    assert.deepEqual(classifyEntry(q), { kind: 'invalid' }, JSON.stringify(q));
  }
});

test('a session notice rides along with the scene on the login URL', () => {
  setActiveScene(null);
  assert.equal(loginUrlForSession('customer_not_onboarded'), '/pages/login/login?notice=customer_not_onboarded');
  setActiveScene('ZCT000001');
  assert.equal(
    loginUrlForSession('customer_not_onboarded'),
    '/pages/login/login?scene=ZCT000001&notice=customer_not_onboarded',
  );
  setActiveScene(null);
});

console.log(`${n} passed`);
