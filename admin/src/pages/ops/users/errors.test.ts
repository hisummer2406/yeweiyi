import { describe, expect, it } from 'vitest';
import { describeUserError, passwordByteLength } from './errors';

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
