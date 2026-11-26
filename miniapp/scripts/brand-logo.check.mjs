// Run with: npm run check   (node --experimental-strip-types; pure logic only, no wx)
import assert from 'node:assert/strict';
import {
  DEFAULT_BRAND_MARK,
  brandLogoFileName,
  brandLogoSrc,
  brandLogoStep,
  knownVersion,
  parseBrandLogo,
} from '../miniprogram/utils/brandLogoCore.ts';

let n = 0;
const test = (name, fn) => {
  fn();
  n++;
  console.log('ok -', name);
};

const cacheA = { account: 'u1', version: 'v1', src: '/data/brand-logo-u1-v1.png' };

test('parses the cloud answer (yeweiyi-cloud docs/brand-logo-contract.md §3)', () => {
  assert.deepEqual(parseBrandLogo({ customerId: 1, configured: false }), { configured: false, version: '', notModified: false, data: '' });
  assert.deepEqual(parseBrandLogo({ configured: true, version: 'v1', data: 'AAAA' }), { configured: true, version: 'v1', notModified: false, data: 'AAAA' });
  assert.deepEqual(parseBrandLogo({ configured: true, version: 'v1', notModified: true }), { configured: true, version: 'v1', notModified: true, data: '' });
  for (const bad of [null, 'x', {}, { configured: true }, { configured: true, version: 'v1' }, { configured: 'yes' }]) {
    assert.equal(parseBrandLogo(bad), null, JSON.stringify(bad));
  }
});

test('only the same account cache supplies ?known= and the picture', () => {
  assert.equal(knownVersion(cacheA, 'u1'), 'v1');
  assert.equal(knownVersion(cacheA, 'u2'), '');
  assert.equal(knownVersion(null, 'u1'), '');
  assert.equal(brandLogoSrc(cacheA, 'u1', DEFAULT_BRAND_MARK), cacheA.src);
  assert.equal(brandLogoSrc(cacheA, 'u2', DEFAULT_BRAND_MARK), DEFAULT_BRAND_MARK);
  assert.equal(brandLogoSrc(cacheA, '', DEFAULT_BRAND_MARK), DEFAULT_BRAND_MARK, 'signed out shows the default');
});

test('a late answer for A after switching to B changes nothing', () => {
  const answer = parseBrandLogo({ configured: true, version: 'v9', data: 'AAAA' });
  assert.deepEqual(brandLogoStep({ requestAccount: 'u1', currentAccount: 'u2', cache: null, answer }), { kind: 'drop' });
  assert.deepEqual(brandLogoStep({ requestAccount: 'u1', currentAccount: '', cache: cacheA, answer }), { kind: 'drop' });
});

test('new version is stored, same version kept, default clears', () => {
  const fresh = parseBrandLogo({ configured: true, version: 'v2', data: 'BBBB' });
  assert.deepEqual(brandLogoStep({ requestAccount: 'u1', currentAccount: 'u1', cache: cacheA, answer: fresh }), { kind: 'store', version: 'v2', data: 'BBBB' });
  const same = parseBrandLogo({ configured: true, version: 'v1', notModified: true });
  assert.deepEqual(brandLogoStep({ requestAccount: 'u1', currentAccount: 'u1', cache: cacheA, answer: same }), { kind: 'keep' });
  const off = parseBrandLogo({ configured: false });
  assert.deepEqual(brandLogoStep({ requestAccount: 'u1', currentAccount: 'u1', cache: cacheA, answer: off }), { kind: 'default' });
  assert.deepEqual(brandLogoStep({ requestAccount: 'u1', currentAccount: 'u1', cache: cacheA, answer: null }), { kind: 'default' });
});

test('"not modified" for a version we do not hold falls back to the default', () => {
  const nm = parseBrandLogo({ configured: true, version: 'v1', notModified: true });
  const otherAccountCache = { ...cacheA, account: 'u7' };
  assert.deepEqual(brandLogoStep({ requestAccount: 'u1', currentAccount: 'u1', cache: otherAccountCache, answer: nm }), { kind: 'default' });
});

test('file names are per account and version, path-safe', () => {
  assert.equal(brandLogoFileName('u12', '9f2c1a0b5d3e4f61'), 'brand-logo-u12-9f2c1a0b5d3e4f61.png');
  assert.equal(brandLogoFileName('u1/../x', 'v.1'), 'brand-logo-u1x-v1.png');
});

console.log(`brand-logo: ${n} checks passed`);
