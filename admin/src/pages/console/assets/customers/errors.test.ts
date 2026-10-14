import { describe, expect, it } from 'vitest';
import {
  describeDeleteError,
  describeOnboardingError,
  describeRenameError,
  isDefaultCustomer,
} from './errors';

describe('describeRenameError', () => {
  it.each([
    [409, 'pages.ops.devices.customer.error.exists'],
    [404, 'pages.ops.customers.error.notFound'],
    [400, 'pages.ops.devices.customer.error.invalid'],
    [403, 'pages.ops.devices.error.forbidden'],
    [0, 'pages.ops.devices.error.network'],
  ])('status %i -> %s', (status, id) => {
    expect(describeRenameError(status, 'x').id).toBe(id);
  });
});

describe('describeDeleteError', () => {
  it('409 means the customer is still in use', () => {
    expect(describeDeleteError(409).id).toBe('pages.ops.customers.error.inUse');
  });
  it('404 means it is already gone', () => {
    expect(describeDeleteError(404).id).toBe(
      'pages.ops.customers.error.notFound',
    );
  });
  it('other statuses fall back to the generic messages', () => {
    expect(describeDeleteError(403).id).toBe(
      'pages.ops.devices.error.forbidden',
    );
  });
});

describe('isDefaultCustomer', () => {
  it('is true only for the customer named default', () => {
    expect(isDefaultCustomer({ id: 1, name: 'default', createdAt: '' })).toBe(
      true,
    );
    expect(isDefaultCustomer({ id: 2, name: 'Default', createdAt: '' })).toBe(
      false,
    );
  });
});

describe('describeOnboardingError', () => {
  it('customer_not_found and 404 mean the customer is gone', () => {
    expect(describeOnboardingError(404, '', 'customer_not_found').id).toBe(
      'pages.ops.customers.error.notFound',
    );
    expect(describeOnboardingError(404).id).toBe(
      'pages.ops.customers.error.notFound',
    );
  });
  it('other statuses fall back to the generic messages', () => {
    expect(describeOnboardingError(0).id).toBe(
      'pages.ops.devices.error.network',
    );
  });
});
