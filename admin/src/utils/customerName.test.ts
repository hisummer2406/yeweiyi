import { describe, expect, it } from 'vitest';
import {
  CUSTOMER_NAME_MAX,
  customerNameCount,
  customerNameLength,
} from './customerName';

describe('customerName', () => {
  it('counts characters, not UTF-16 units', () => {
    expect(customerNameLength('中成电气')).toBe(4);
    expect(customerNameLength('😀😀')).toBe(2);
  });
  it('truncates at the limit by characters', () => {
    const over = '客'.repeat(CUSTOMER_NAME_MAX + 5);
    const cut = customerNameCount.exceedFormatter(over, {
      max: CUSTOMER_NAME_MAX,
    });
    expect(customerNameLength(cut)).toBe(CUSTOMER_NAME_MAX);
  });
});
