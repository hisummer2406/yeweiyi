// Run with: npm run check   (node --experimental-strip-types; pure logic only, no wx)
import assert from 'node:assert/strict';
import { en } from '../miniprogram/locales/en.ts';
import { es } from '../miniprogram/locales/es.ts';
import { zh } from '../miniprogram/locales/zh.ts';
import { describeResolveFailure, parseDeviceCodeQr } from '../miniprogram/utils/pairing.ts';
import { isReadOnlyRole } from '../miniprogram/utils/roles.ts';

let n = 0;
const test = (name, fn) => {
  fn();
  n++;
  console.log('ok -', name);
};

test('scanned text is passed through verbatim: leading zeros kept, nothing padded or prefixed', () => {
  assert.equal(parseDeviceCodeQr('000001'), '000001');
  assert.equal(parseDeviceCodeQr('  000123\n'), '000123');
  assert.equal(parseDeviceCodeQr('ZCT930001'), 'ZCT930001');
  assert.equal(parseDeviceCodeQr('AB-12_x'), 'AB-12_x');
});

test('no assumed prefix or length: 1 and 64 characters are both fine, 65 is not', () => {
  assert.equal(parseDeviceCodeQr('7'), '7');
  assert.equal(parseDeviceCodeQr('a'.repeat(64)), 'a'.repeat(64));
  assert.equal(parseDeviceCodeQr('a'.repeat(65)), null);
});

test('anything that is not a bare code is refused before a request is spent', () => {
  for (const bad of ['', '   ', 'https://x.example/p?d=000001', 'a b', '设备001', '000001;drop']) {
    assert.equal(parseDeviceCodeQr(bad), null, bad);
  }
});

test('device_not_found is one message for unknown / other customer / not granted', () => {
  const m = describeResolveFailure(404, 'device_not_found');
  assert.equal(m, 'scanErr.notFound');
  // one answer, and it points to the platform administrator in every language
  assert.match(zh[m], /联系平台管理员/);
  assert.match(en[m], /platform administrator/i);
  assert.match(es[m], /administrador de la plataforma/i);
});

test('410 bind_removed is not shown as a session problem; other failures keep their own text', () => {
  assert.equal(describeResolveFailure(410, 'bind_removed'), 'scanErr.bindRemoved');
  // no scan-specific wording: '' hands over to the generic error text (never the cloud's own)
  assert.equal(describeResolveFailure(500, ''), '');
  assert.equal(describeResolveFailure(-1, ''), '');
});

test('only customer_user is view-only; unknown or empty role is left to the server', () => {
  assert.equal(isReadOnlyRole('customer_user'), true);
  assert.equal(isReadOnlyRole('customer_admin'), false);
  assert.equal(isReadOnlyRole('platform_admin'), false);
  assert.equal(isReadOnlyRole(''), false);
});

console.log(`${n} passed`);
