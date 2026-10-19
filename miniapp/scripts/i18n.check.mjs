// Run with: npm run check   (node --experimental-strip-types; pure logic only, no wx)
// The three language tables must have the same keys and placeholders, every key the code names must
// exist, and the language rules (default, fallback) must hold.
import assert from 'node:assert/strict';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { en } from '../miniprogram/locales/en.ts';
import { es } from '../miniprogram/locales/es.ts';
import { zh } from '../miniprogram/locales/zh.ts';
import {
  FALLBACK,
  LOCALES,
  compareTables,
  fill,
  pickLocale,
  placeholdersOf,
  scope,
  translate,
} from '../miniprogram/utils/i18nCore.ts';
import { errorKey } from '../miniprogram/utils/errorKey.ts';

let n = 0;
const test = (name, fn) => {
  fn();
  n++;
  console.log('ok -', name);
};
const tables = { zh, en, es };

test('zh, en and es have the same keys, the same placeholders and no empty text', () => {
  assert.deepEqual(compareTables(tables), []);
});

test('compareTables names a missing, an extra and a mismatched-placeholder key', () => {
  const broken = {
    zh,
    en: { ...en, 'x.only': 'only en' },
    es: { ...es, 'oilNote.legacySkipped': 'no placeholder here' },
  };
  delete broken.es['common.more'];
  const problems = compareTables(broken);
  const has = (locale, key, problem) =>
    problems.some((p) => p.locale === locale && p.key === key && p.problem === problem);
  assert.ok(has('zh', 'x.only', 'missing'));
  assert.ok(has('es', 'common.more', 'missing'));
  assert.ok(has('es', 'oilNote.legacySkipped', 'placeholders'));
});

test('the language starts as the user chose, else the phone language, else English', () => {
  assert.equal(pickLocale('zh_CN', undefined), 'zh');
  assert.equal(pickLocale('zh-Hans', undefined), 'zh');
  assert.equal(pickLocale('es-MX', undefined), 'es');
  assert.equal(pickLocale('es', undefined), 'es');
  assert.equal(pickLocale('en_US', undefined), 'en');
  assert.equal(pickLocale('fr_FR', undefined), 'en');
  assert.equal(pickLocale(undefined, undefined), 'en');
  assert.equal(pickLocale('', undefined), 'en');
  // a saved choice wins over the phone, and a saved value that is not a language is ignored
  assert.equal(pickLocale('zh_CN', 'es'), 'es');
  assert.equal(pickLocale('es', 'en'), 'en');
  assert.equal(pickLocale('es', 'klingon'), 'es');
  assert.equal(pickLocale('es', 42), 'es');
});

test('a missing text falls back to English, then to the key itself, never to blank', () => {
  const t = { zh: {}, en: { 'a.b': 'English' }, es: { 'a.b': 'Español' } };
  assert.equal(translate(t, 'zh', 'a.b'), 'English');
  assert.equal(translate(t, 'es', 'a.b'), 'Español');
  assert.equal(translate(t, 'es', 'a.nope'), 'a.nope');
});

test('placeholders are filled; an unknown one stays visible', () => {
  assert.equal(fill('{n} records, {parsed} shown', { n: 3, parsed: 2 }), '3 records, 2 shown');
  assert.equal(fill('{n} and {typo}', { n: 1 }), '1 and {typo}');
  assert.equal(fill('plain'), 'plain');
  assert.deepEqual(placeholdersOf('{b} {a} {b}'), ['a', 'b']);
  assert.equal(translate(tables, 'en', 'oilNote.legacySkipped', { n: 4 }).includes('4'), true);
});

test('scope returns one page\'s texts with the fallback filling gaps and ignores deeper keys', () => {
  const t = {
    zh: {},
    en: { 'login.title': 'Title', 'login.btn': 'Go', 'login.deep.key': 'x', 'other.title': 'no' },
    es: { 'login.title': 'Título' },
  };
  assert.deepEqual(scope(t, 'es', 'login'), { title: 'Título', btn: 'Go' });
});

test('error wording follows the cloud code first, then the status; 0 is "no answer"', () => {
  assert.equal(errorKey(0), 'err.network');
  assert.equal(errorKey(403, 'tank_not_authorized'), 'err.tankNotAuthorized');
  assert.equal(errorKey(404, 'device_not_found'), 'err.deviceNotFound');
  assert.equal(errorKey(401), 'err.sessionExpired');
  assert.equal(errorKey(403), 'err.forbidden');
  assert.equal(errorKey(409), 'err.conflict');
  assert.equal(errorKey(502), 'err.server');
  assert.equal(errorKey(400, 'something_new'), 'err.generic');
  for (const k of ['err.network', 'err.sessionExpired', 'err.forbidden', 'err.notFound', 'err.conflict', 'err.server', 'err.generic']) {
    assert.ok(en[k], k);
  }
});

// Every key the code names must exist. Keys are string literals of the form group.name, where `group`
// is one of the groups the tables define.
function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    if (name === 'miniprogram_npm' || name === 'locales' || name === 'types') continue;
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (/\.(ts|wxml)$/.test(name)) out.push(p);
  }
  return out;
}

test('every i18n key written in the code exists in the tables', () => {
  const groups = new Set(Object.keys(en).map((k) => k.split('.')[0]));
  const missing = [];
  for (const file of walk('miniprogram')) {
    const src = readFileSync(file, 'utf8');
    for (const m of src.matchAll(/'([A-Za-z]+(?:\.[A-Za-z0-9_]+)+)'/g)) {
      const key = m[1];
      if (!groups.has(key.split('.')[0])) continue;
      if (!(key in en)) missing.push(`${file}: ${key}`);
    }
    // texts('group') must name a group that exists
    for (const m of src.matchAll(/\btexts\('([A-Za-z]+)'\)/g)) {
      if (!groups.has(m[1])) missing.push(`${file}: texts('${m[1]}')`);
    }
  }
  assert.deepEqual(missing, []);
});

test('the fallback language is English and all three languages are supported', () => {
  assert.equal(FALLBACK, 'en');
  assert.deepEqual([...LOCALES], ['zh', 'en', 'es']);
});

console.log(`${n} checks passed`);
