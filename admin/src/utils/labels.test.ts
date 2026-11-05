import { describe, expect, it } from 'vitest';
import {
  ALARM_SEVERITY_LABELS,
  ALARM_STATUS_LABELS,
  ALARM_TYPE_LABELS,
  label,
  STOCK_DIRECTION_LABELS,
  STOCK_SOURCE_LABELS,
} from './labels';

describe('label', () => {
  it('maps known enum values', () => {
    expect(label(ALARM_TYPE_LABELS, 'HIGH_LEVEL')).toBe('高液位');
    expect(label(ALARM_SEVERITY_LABELS, 'CRITICAL')).toBe('紧急');
    expect(label(ALARM_STATUS_LABELS, 'ACTIVE')).toBe('报警中');
    expect(label(ALARM_STATUS_LABELS, 'CLEARED')).toBe('已恢复');
    expect(label(STOCK_DIRECTION_LABELS, 'IN')).toBe('进油');
    expect(label(STOCK_DIRECTION_LABELS, 'OUT')).toBe('出油');
    expect(label(STOCK_SOURCE_LABELS, 'AUTO')).toBe('自动');
    expect(label(STOCK_SOURCE_LABELS, 'MANUAL')).toBe('手动');
  });

  it('shows an unknown value as itself, never blank', () => {
    expect(label(ALARM_TYPE_LABELS, 'NEW_TYPE')).toBe('NEW_TYPE');
  });

  it('shows -- for an empty value', () => {
    expect(label(ALARM_TYPE_LABELS, '')).toBe('--');
    expect(label(ALARM_TYPE_LABELS, undefined)).toBe('--');
    expect(label(ALARM_TYPE_LABELS, null)).toBe('--');
  });

  it('does not treat Object.prototype members as labels', () => {
    expect(label(ALARM_TYPE_LABELS, 'constructor')).toBe('constructor');
    expect(label(ALARM_TYPE_LABELS, 'toString')).toBe('toString');
  });
});
