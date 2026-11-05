import { describe, expect, it } from 'vitest';
import { alarmColor, alarmLabel, fmt, fmtTime } from './telemetry';

describe('telemetry utils', () => {
  it('maps alarm states, keeping unknown values as is', () => {
    expect(alarmLabel('ALARM')).toBe('报警');
    expect(alarmLabel('FAULT')).toBe('故障');
    expect(alarmLabel('NORMAL')).toBe('正常');
    expect(alarmLabel('X')).toBe('X');
    expect(alarmColor('ALARM')).toBe('error');
    expect(alarmColor('FAULT')).toBe('warning');
  });

  it('formats numbers and times defensively', () => {
    expect(fmt(12.345)).toBe('12.3');
    expect(fmt(Number.NaN)).toBe('--');
    expect(fmt(undefined)).toBe('--');
    expect(fmtTime(null)).toBe('--');
    expect(fmtTime('bad')).toBe('--');
    expect(fmtTime('2026-10-07T01:02:03Z')).toMatch(
      /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/,
    );
  });
});
