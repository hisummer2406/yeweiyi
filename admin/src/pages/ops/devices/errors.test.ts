import { describe, expect, it } from 'vitest';
import { describeCustomerError, describeRegisterError } from './errors';

describe('describeRegisterError', () => {
  it('409 says the device belongs to another customer', () => {
    expect(
      describeRegisterError(
        409,
        'device already belongs to a different customer',
      ),
    ).toEqual({ id: 'pages.ops.devices.error.conflict' });
  });

  it('400 passes the backend error text through', () => {
    expect(describeRegisterError(400, 'unknown customerId')).toEqual({
      id: 'pages.ops.devices.error.invalid',
      detail: 'unknown customerId',
    });
  });

  it('400 without a body still has a message', () => {
    expect(describeRegisterError(400)).toEqual({
      id: 'pages.ops.devices.error.invalid',
      detail: '',
    });
  });

  it('403 says there is no permission', () => {
    expect(describeRegisterError(403, 'only a platform administrator')).toEqual(
      { id: 'pages.ops.devices.error.forbidden' },
    );
  });

  it('a network failure (status 0) is told apart from a server failure', () => {
    expect(describeRegisterError(0)).toEqual({
      id: 'pages.ops.devices.error.network',
    });
  });

  it.each([500, 502, 418])('%i falls back to a generic message', (status) => {
    expect(describeRegisterError(status, 'boom')).toEqual({
      id: 'pages.ops.devices.error.unknown',
      detail: 'boom',
    });
  });

  it('never reads as a success or a conflict for unknown statuses', () => {
    expect(describeRegisterError(500).id).not.toBe(
      'pages.ops.devices.error.conflict',
    );
  });
});

describe('describeCustomerError', () => {
  it('409 says the name already exists', () => {
    expect(
      describeCustomerError(409, 'a customer with this name already exists'),
    ).toEqual({ id: 'pages.ops.devices.customer.error.exists' });
  });

  it('400 passes the backend error text through', () => {
    expect(describeCustomerError(400, 'name must be 1-100 characters')).toEqual(
      {
        id: 'pages.ops.devices.customer.error.invalid',
        detail: 'name must be 1-100 characters',
      },
    );
  });

  it('403 and others reuse the register messages', () => {
    expect(describeCustomerError(403)).toEqual({
      id: 'pages.ops.devices.error.forbidden',
    });
    expect(describeCustomerError(0)).toEqual({
      id: 'pages.ops.devices.error.network',
    });
    expect(describeCustomerError(500, 'boom')).toEqual({
      id: 'pages.ops.devices.error.unknown',
      detail: 'boom',
    });
  });
});
