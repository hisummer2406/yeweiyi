// Run with: npm run check   (node --experimental-strip-types; pure logic only, no wx)
import assert from 'node:assert/strict';
import {
  classifyPhoneAuth,
  codeOfBody,
  describeLoginFailure,
  maskPhone,
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
  assert.equal(f.message, '');
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
    assert.ok(f.message, code);
    assert.ok(!seen.has(f.message), `${code} shares its message`);
    seen.add(f.message);
  }
});

test('not onboarded, unprovisioned and disabled are never confused with each other or with a bad password', () => {
  const a = describeLoginFailure(403, 'customer_not_onboarded').message;
  const b = describeLoginFailure(404, 'phone_not_provisioned').message;
  const c = describeLoginFailure(403, 'account_disabled').message;
  assert.match(a, /尚未完成开通/);
  assert.match(b, /尚未开通/);
  assert.match(c, /已停用/);
  for (const m of [a, b, c]) assert.doesNotMatch(m, /密码/);
});

test('network failure (status 0) and server errors are retryable and say so', () => {
  assert.match(describeLoginFailure(0).message, /网络/);
  assert.match(describeLoginFailure(503).message, /稍后/);
  assert.equal(describeLoginFailure(0).offerPhone, false);
});

test('an unknown code with a client status falls back to a generic message, not a blank', () => {
  assert.ok(describeLoginFailure(418, 'something_new').message.length > 0);
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
  assert.match(noPerm.message, /未开通手机号授权/);
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

console.log(`${n} checks passed`);
