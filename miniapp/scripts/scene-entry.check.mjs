// Run with: npm run check   (node --experimental-strip-types; pure logic only, no wx)
import assert from 'node:assert/strict';
import { detailUrlForScene, loginUrlForScene, loginUrlForSession, sceneFromQuery, setActiveScene } from '../miniprogram/utils/sceneEntry.ts';

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

console.log(`${n} passed`);
