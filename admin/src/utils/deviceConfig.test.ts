import { describe, expect, it } from 'vitest';
import {
  aboveTankHeight,
  changedArgs,
  checkArgs,
  classifySubmitError,
  commandPhase,
  DEVICE_FIELDS,
  echoState,
  newRequestId,
  TANK_FIELDS,
  targetTank,
} from './deviceConfig';

const tank = {
  tank: 1,
  reportedAt: '2026-11-08T14:00:07Z',
  tankHeightMm: 2500,
  alarmHighHighLevelMm: 2300,
  alarmHighLevelMm: 600,
  alarmLowLevelMm: 300,
  alarmLowLowLevelMm: 0,
  alarmHighTempC: null,
  density: 0.56,
} as unknown as API.TankSettings;

describe('changedArgs', () => {
  it('sends only what changed and editable', () => {
    expect(
      changedArgs(TANK_FIELDS, tank as never, {
        alarmHighLevelMm: 610,
        alarmLowLevelMm: 300, // unchanged
        density: 0.7, // read-only
        alarmHighTempC: undefined,
      }),
    ).toEqual({ alarmHighLevelMm: 610 });
  });
  it('counts filling a never-reported field as a change', () => {
    expect(
      changedArgs(TANK_FIELDS, tank as never, { alarmHighTempC: 60 }),
    ).toEqual({
      alarmHighTempC: 60,
    });
  });
  it('treats an empty draft as no change', () => {
    expect(changedArgs(TANK_FIELDS, tank as never, {})).toEqual({});
  });
});

describe('checkArgs', () => {
  it('flags range and integer problems', () => {
    expect(
      checkArgs(TANK_FIELDS, tank as never, { alarmHighTempC: 120 }),
    ).toEqual([{ field: 'alarmHighTempC', code: 'range' }]);
    expect(
      checkArgs(TANK_FIELDS, tank as never, { alarmHighLevelMm: 610.5 }),
    ).toEqual([{ field: 'alarmHighLevelMm', code: 'integer' }]);
    expect(
      checkArgs(DEVICE_FIELDS, null, { inStockTimeThresholdSec: 0 }),
    ).toEqual([{ field: 'inStockTimeThresholdSec', code: 'range' }]);
  });
  it('checks the level order on merged values, 0 = disabled', () => {
    expect(
      checkArgs(TANK_FIELDS, tank as never, { alarmHighLevelMm: 610 }),
    ).toEqual([]);
    expect(
      checkArgs(TANK_FIELDS, tank as never, { alarmHighLevelMm: 250 }),
    ).toEqual([{ field: 'alarmHighLevelMm', code: 'order' }]);
    // Disabling a level removes it from the order.
    expect(
      checkArgs(TANK_FIELDS, tank as never, {
        alarmLowLevelMm: 0,
        alarmHighLevelMm: 100,
      }),
    ).toEqual([]);
  });
  it('ignores the order when no level is touched', () => {
    const bad = { ...tank, alarmHighLevelMm: 100 } as never;
    expect(checkArgs(TANK_FIELDS, bad, { capacityL: 30000 })).toEqual([]);
  });
  it('warns above tank height', () => {
    expect(
      aboveTankHeight(tank, { alarmHighHighLevelMm: 2600, capacityL: 9999 }),
    ).toEqual(['alarmHighHighLevelMm']);
  });
});

describe('newRequestId', () => {
  it('matches the cloud pattern and differs per call', () => {
    const a = newRequestId();
    expect(a).toMatch(/^[A-Za-z0-9_-]{1,64}$/);
    expect(newRequestId()).not.toBe(a);
  });
});

describe('commandPhase', () => {
  it('maps statuses; ACK is not success', () => {
    expect(commandPhase({ status: 'sent' })).toBe('accepted');
    expect(commandPhase({ status: 'received' })).toBe('received');
    expect(commandPhase({ status: 'weird' })).toBe('unknown');
  });
});

describe('echoState', () => {
  const issued = '2026-11-08T14:00:05Z';
  it('waits for a report newer than the command', () => {
    expect(
      echoState(
        { reportedAt: '2026-11-08T13:00:00Z', alarmHighLevelMm: 610 },
        { tank: 1, alarmHighLevelMm: 610 },
        issued,
      ),
    ).toBe('waiting');
    expect(echoState(null, { alarmHighLevelMm: 610 }, issued)).toBe('waiting');
  });
  it('matches or mismatches on a fresh report', () => {
    expect(
      echoState(
        { reportedAt: '2026-11-08T14:00:07Z', alarmHighLevelMm: 610 },
        { tank: 1, alarmHighLevelMm: 610 },
        issued,
      ),
    ).toBe('matched');
    expect(
      echoState(
        { reportedAt: '2026-11-08T14:00:07Z', alarmHighLevelMm: 600 },
        { tank: 1, alarmHighLevelMm: 610 },
        issued,
      ),
    ).toBe('mismatch');
  });
});

describe('classifySubmitError', () => {
  it('branches on code and scope', () => {
    expect(
      classifySubmitError({
        status: 409,
        code: 'config_commands_disabled',
        data: { scope: 'device' },
      }),
    ).toBe('deviceDisabled');
    expect(
      classifySubmitError({
        status: 409,
        code: 'config_commands_disabled',
        data: { scope: 'deployment' },
      }),
    ).toBe('deploymentDisabled');
    expect(
      classifySubmitError({ status: 409, code: 'device_station_changed' }),
    ).toBe('stationChanged');
    expect(
      classifySubmitError({ status: 409, code: 'config_command_in_flight' }),
    ).toBe('inFlight');
    expect(classifySubmitError({ status: 0 })).toBe('unconfirmed');
    expect(
      classifySubmitError({
        status: 503,
        code: 'command_delivery_unconfirmed',
      }),
    ).toBe('unconfirmed');
    expect(classifySubmitError({ status: 500 })).toBe('other');
  });
});

it('targetTank', () => {
  expect(targetTank('tank:3')).toBe(3);
  expect(targetTank('device')).toBeUndefined();
});
