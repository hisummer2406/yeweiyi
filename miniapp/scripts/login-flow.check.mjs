// Run with: npm run check   (node --experimental-strip-types; pure logic only, no wx)
import assert from 'node:assert/strict';
import { en } from '../miniprogram/locales/en.ts';
import { es } from '../miniprogram/locales/es.ts';
import { zh } from '../miniprogram/locales/zh.ts';
import {
  classifyPhoneAuth,
  codeOfBody,
  describeLoginFailure,
  isNotOnboarded,
  sessionDecision,
  maskPhone,
  buildPasswordLogin,
  noticeFromQuery,
} from '../miniprogram/utils/loginFlow.ts';

let n = 0;
const test = (name, fn) => {
  fn();
  n++;
  console.log('ok -', name);
};

test('an unbound WeChat offers the phone authorization and shows no error', () => {
  const f = describeLoginFailure(401, 'wechat_not_bound');
  assert.equal(f.offerPhone, true);
  assert.equal(f.messageKey, '');
});

test('every other failure shows a distinct message and never offers the phone button', () => {
  const codes = [
    'phone_not_provisioned',
    'account_disabled',
    'customer_not_onboarded',
    'wechat_identity_conflict',
    'wechat_code_invalid',
    'wechat_unavailable',
    'wechat_upstream_error',
    'phone_unsupported',
  ];
  const seen = new Set();
  for (const code of codes) {
    const f = describeLoginFailure(400, code);
    assert.equal(f.offerPhone, false, code);
    assert.ok(f.messageKey, code);
    assert.ok(en[f.messageKey], `${code} names a text that does not exist`);
    assert.ok(!seen.has(f.messageKey), `${code} shares its message`);
    seen.add(f.messageKey);
  }
});

test('a rate-limited login (429) has its own wording, not a password error', () => {
  const r = describeLoginFailure(429, 'login_rate_limited');
  assert.equal(r.messageKey, 'loginErr.login_rate_limited');
  assert.equal(r.offerPhone, false);
  assert.notEqual(r.messageKey, describeLoginFailure(401, 'invalid_credentials').messageKey);
});

test('not onboarded, unprovisioned and disabled are never confused with each other or with a bad password', () => {
  const a = describeLoginFailure(403, 'customer_not_onboarded').messageKey;
  const b = describeLoginFailure(404, 'phone_not_provisioned').messageKey;
  const c = describeLoginFailure(403, 'account_disabled').messageKey;
  const bad = describeLoginFailure(401, 'invalid_credentials').messageKey;
  assert.equal(a, 'loginErr.customer_not_onboarded');
  assert.equal(b, 'loginErr.phone_not_provisioned');
  assert.equal(c, 'loginErr.account_disabled');
  assert.equal(new Set([a, b, c, bad]).size, 4);
  // none of the three may be worded as a password problem, in any language
  for (const key of [a, b, c]) {
    assert.doesNotMatch(zh[key], /密码/);
    assert.doesNotMatch(en[key], /password/i);
    assert.doesNotMatch(es[key], /contraseña/i);
  }
});

test('network failure (status 0) and server errors are retryable and say so', () => {
  assert.equal(describeLoginFailure(0).messageKey, 'loginErr.offline');
  assert.equal(describeLoginFailure(503).messageKey, 'loginErr.server');
  // retryable: the wording tells the user to try again, in every language
  for (const key of ['loginErr.offline', 'loginErr.server']) {
    assert.match(zh[key], /重试/);
    assert.match(en[key], /try again/i);
    assert.match(es[key], /inténtelo/i);
  }
  assert.equal(describeLoginFailure(0).offerPhone, false);
});

test('an unknown code with a client status falls back to a generic message, not a blank', () => {
  assert.equal(describeLoginFailure(418, 'something_new').messageKey, 'loginErr.unknown');
  assert.equal(describeLoginFailure(401, undefined).offerPhone, false);
});

test('codeOfBody reads only a string code', () => {
  assert.equal(codeOfBody({ code: 'wechat_not_bound', error: 'x' }), 'wechat_not_bound');
  assert.equal(codeOfBody({ code: 7 }), undefined);
  assert.equal(codeOfBody(undefined), undefined);
  assert.equal(codeOfBody('oops'), undefined);
});

test('getPhoneNumber: a code means go; a refusal is a normal answer; other failures say why', () => {
  assert.deepEqual(classifyPhoneAuth({ errMsg: 'getPhoneNumber:ok', code: 'abc' }), { kind: 'ok', code: 'abc' });
  assert.deepEqual(classifyPhoneAuth({ errMsg: 'getPhoneNumber:fail user deny' }), { kind: 'denied' });
  assert.deepEqual(classifyPhoneAuth({ errMsg: 'getPhoneNumber:fail cancel' }), { kind: 'denied' });
  const noPerm = classifyPhoneAuth({ errMsg: 'getPhoneNumber:fail no permission' });
  assert.equal(noPerm.kind, 'failed');
  assert.equal(noPerm.messageKey, 'loginErr.phoneNoPermission');
  assert.equal(classifyPhoneAuth(undefined).kind, 'failed');
  assert.equal(classifyPhoneAuth({}).kind, 'failed');
});

test('a "denied" answer carrying no code is never treated as success', () => {
  assert.notEqual(classifyPhoneAuth({ errMsg: 'getPhoneNumber:fail user deny', code: '' }).kind, 'ok');
});

test('maskPhone keeps 3 + 4 digits and refuses anything that is not a mainland number', () => {
  assert.equal(maskPhone('13800138000'), '138****8000');
  assert.equal(maskPhone('138****8000'), '');
  assert.equal(maskPhone(''), '');
  assert.equal(maskPhone(null), '');
  assert.equal(maskPhone(undefined), '');
});

test('customer_not_onboarded: its own notice key, only 403 + that code, not the dead-account 403', () => {
  const msg = 'loginErr.customer_not_onboarded';
  assert.equal(describeLoginFailure(403, 'customer_not_onboarded').messageKey, msg);
  assert.equal(isNotOnboarded(403, { error: 'customer not onboarded', code: 'customer_not_onboarded' }), true);
  assert.equal(isNotOnboarded(403, { error: 'not authorized' }), false);
  assert.equal(isNotOnboarded(403, { error: 'not authorized for this device' }), false);
  assert.equal(isNotOnboarded(401, { code: 'customer_not_onboarded' }), false);
  assert.equal(noticeFromQuery({ notice: 'customer_not_onboarded' }), msg);
  assert.equal(noticeFromQuery({ notice: 'x' }), '');
  assert.equal(noticeFromQuery({}), '');
});

const base = { needAuth: true, keepSessionOn401: false, sentToken: 'old', currentToken: 'old' };
const NOT_ONBOARDED = { error: 'customer not onboarded', code: 'customer_not_onboarded' };

test('sessionDecision: not onboarded ends the session with the notice; dead account without it', () => {
  assert.deepEqual(sessionDecision({ ...base, status: 403, body: NOT_ONBOARDED }), { end: true, notice: 'customer_not_onboarded' });
  assert.deepEqual(sessionDecision({ ...base, status: 403, body: { error: 'not authorized' } }), { end: true });
  assert.deepEqual(sessionDecision({ ...base, status: 401, body: { error: 'expired' } }), { end: true });
});

test('sessionDecision: other failures and login calls never end the session', () => {
  assert.deepEqual(sessionDecision({ ...base, status: 403, body: { error: 'not authorized for this device' } }), { end: false });
  assert.deepEqual(sessionDecision({ ...base, status: 403, body: { code: 'tank_not_authorized' } }), { end: false });
  assert.deepEqual(sessionDecision({ ...base, status: 500, body: undefined }), { end: false });
  assert.deepEqual(sessionDecision({ ...base, status: 401, body: {}, keepSessionOn401: true }), { end: false });
  assert.deepEqual(sessionDecision({ ...base, needAuth: false, status: 403, body: NOT_ONBOARDED }), { end: false });
  assert.deepEqual(sessionDecision({ ...base, needAuth: false, status: 401, body: {} }), { end: false });
});

test('sessionDecision: an answer for a replaced session never clears the new one', () => {
  const signedInAgain = { ...base, sentToken: 'old', currentToken: 'new' };
  assert.deepEqual(sessionDecision({ ...signedInAgain, status: 403, body: NOT_ONBOARDED }), { end: false });
  assert.deepEqual(sessionDecision({ ...signedInAgain, status: 401, body: {} }), { end: false });
  // a request that went out bare answers 401 after a new sign-in
  assert.deepEqual(sessionDecision({ ...base, sentToken: '', currentToken: 'new', status: 401, body: {} }), { end: false });
});

test('sessionDecision: still signed out, a bare queued request still ends the session', () => {
  assert.deepEqual(sessionDecision({ ...base, sentToken: '', currentToken: '', status: 401, body: {} }), { end: true });
});

test('password login: one identifier, phone or username decided by the input (same as admin)', () => {
  const phone = { ok: true, body: { phone: '13800138000', password: 'p w' } };
  assert.deepEqual(buildPasswordLogin(' 138 0013-8000 ', 'p w'), phone);
  assert.deepEqual(buildPasswordLogin('+86 13800138000', 'p w'), phone);
  assert.deepEqual(buildPasswordLogin('008613800138000', 'p w'), phone);
  assert.deepEqual(buildPasswordLogin(' admin ', 'x'), { ok: true, body: { username: 'admin', password: 'x' } });
  // 11 digits that are not a mainland mobile number are a username, not a malformed phone
  assert.deepEqual(buildPasswordLogin('12345678901', 'x'), { ok: true, body: { username: '12345678901', password: 'x' } });
});

test('password login: empty fields are told apart from a wrong password', () => {
  assert.equal(buildPasswordLogin('  ', 'x').messageKey, 'loginErr.needAccount');
  assert.equal(buildPasswordLogin('admin', '').messageKey, 'loginErr.needPassword');
});

test('password login: invalid_credentials is its own message and never offers the phone button', () => {
  const f = describeLoginFailure(401, 'invalid_credentials');
  assert.equal(f.offerPhone, false);
  assert.equal(f.messageKey, 'loginErr.invalid_credentials');
});

console.log(`${n} checks passed`);
