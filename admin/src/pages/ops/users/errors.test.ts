import { describe, expect, it } from 'vitest';
import {
  describeUserError,
  isValidCustomerUsername,
  passwordByteLength,
} from './errors';

describe('describeUserError', () => {
  it.each([
    [409, 'pages.ops.users.error.exists'],
    [400, 'pages.ops.users.error.invalid'],
    [403, 'pages.ops.users.error.forbidden'],
    [404, 'pages.ops.users.error.notFound'],
    [0, 'pages.ops.users.error.network'],
    [500, 'pages.ops.users.error.unknown'],
  ])('status %i maps to %s', (status, id) => {
    expect(describeUserError(status, 'x').id).toBe(id);
  });

  it('carries the backend text as detail for 400', () => {
    expect(describeUserError(400, 'password must be 8-72 bytes').detail).toBe(
      'password must be 8-72 bytes',
    );
  });
});

describe('passwordByteLength', () => {
  it('counts UTF-8 bytes, not characters', () => {
    expect(passwordByteLength('abcdefgh')).toBe(8);
    expect(passwordByteLength('密码密码')).toBe(12);
  });
});

describe('describeUserError by code', () => {
  it.each([
    [400, 'invalid_username', 'pages.ops.users.error.invalidUsername'],
    [409, 'username_taken', 'pages.ops.users.error.usernameTaken'],
    [400, 'invalid_password', 'pages.ops.users.error.invalidPassword'],
    [409, 'phone_exists', 'pages.ops.users.error.phoneExists'],
  ])('%i %s maps to %s', (status, code, id) => {
    expect(describeUserError(status, 'x', code).id).toBe(id);
  });
});

describe('isValidCustomerUsername', () => {
  it.each(['abc', 'Staff.One', 'a_b-c', 'u'.repeat(32), '1abc'])(
    'accepts %s',
    (v) => expect(isValidCustomerUsername(v)).toBe(true),
  );
  it.each([
    'ab',
    'u'.repeat(33),
    '.abc',
    '-abc',
    'a b c',
    '张三三',
    '13900139000',
  ])('rejects %s', (v) => expect(isValidCustomerUsername(v)).toBe(false));
});
