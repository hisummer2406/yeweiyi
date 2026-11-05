import { describe, expect, it } from 'vitest';
import { describeTankGrantError, isStaleGrantError } from './errors';

describe('describeTankGrantError', () => {
  it.each([
    ['tanks_unknown', 409, 'pages.console.staff.error.tanksUnknown'],
    ['invalid_tank', 400, 'pages.console.staff.error.invalidTank'],
    ['invalid_request', 400, 'pages.console.staff.error.invalid'],
    [
      'device_customer_mismatch',
      409,
      'pages.console.staff.error.customerMismatch',
    ],
    ['grant_not_applicable', 409, 'pages.console.staff.error.notApplicable'],
    ['user_not_found', 404, 'pages.console.staff.error.userNotFound'],
    ['device_not_found', 404, 'pages.console.staff.error.deviceNotFound'],
  ])('code %s -> %s regardless of the error text', (code, status, id) => {
    expect(describeTankGrantError(status, code, 'whatever').id).toBe(id);
  });

  it('without a code falls back by status', () => {
    expect(describeTankGrantError(503).id).toBe(
      'pages.console.staff.error.unavailable',
    );
    expect(describeTankGrantError(0).id).toBe(
      'pages.console.staff.error.network',
    );
    expect(describeTankGrantError(500, undefined, 'boom')).toEqual({
      id: 'pages.console.staff.error.unknown',
    });
  });

  it('stale codes are the ones that need a refresh', () => {
    expect(isStaleGrantError('invalid_tank')).toBe(true);
    expect(isStaleGrantError('tanks_unknown')).toBe(true);
    expect(isStaleGrantError('invalid_request')).toBe(false);
    expect(isStaleGrantError(undefined)).toBe(false);
  });
});
