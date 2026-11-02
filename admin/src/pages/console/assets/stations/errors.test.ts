import { describe, expect, it } from 'vitest';
import {
  describeAssignStationError,
  describeDeleteStationError,
  describeReplaceError,
  describeStationError,
} from './errors';

describe('describeStationError', () => {
  it('maps by status code, never by message text', () => {
    expect(describeStationError(409, 'anything').id).toBe(
      'pages.console.stations.error.exists',
    );
    expect(describeStationError(404).id).toBe(
      'pages.console.stations.error.notFound',
    );
    expect(describeStationError(403).id).toBe(
      'pages.console.stations.error.forbidden',
    );
    expect(describeStationError(0).id).toBe(
      'pages.console.stations.error.network',
    );
    expect(describeStationError(400, 'bad name')).toEqual({
      id: 'pages.console.stations.error.invalid',
    });
    expect(describeStationError(500, 'boom')).toEqual({
      id: 'pages.console.stations.error.unknown',
    });
  });
});

describe('describeDeleteStationError', () => {
  it('409 means the station still has devices, not a name clash', () => {
    expect(describeDeleteStationError(409).id).toBe(
      'pages.console.stations.error.inUse',
    );
    expect(describeDeleteStationError(404).id).toBe(
      'pages.console.stations.error.notFound',
    );
    expect(describeDeleteStationError(500, 'x').id).toBe(
      'pages.console.stations.error.unknown',
    );
  });
});

describe('describeAssignStationError', () => {
  it('distinguishes bad station, no customer and missing device', () => {
    expect(describeAssignStationError(400).id).toBe(
      'pages.console.stations.assign.error.badStation',
    );
    expect(describeAssignStationError(409).id).toBe(
      'pages.console.stations.assign.error.conflict',
    );
    expect(describeAssignStationError(404).id).toBe(
      'pages.console.stations.assign.error.notFound',
    );
    expect(describeAssignStationError(403).id).toBe(
      'pages.console.stations.error.forbidden',
    );
  });
});

describe('describeAssignStationError by code', () => {
  it('station_occupied names the occupant; device_without_customer is its own message', () => {
    expect(
      describeAssignStationError(409, 'x', 'station_occupied', '000777'),
    ).toEqual({
      id: 'pages.console.stations.assign.error.occupiedBy',
      detail: '000777',
    });
    expect(
      describeAssignStationError(409, 'x', 'device_without_customer').id,
    ).toBe('pages.console.stations.assign.error.noCustomer');
  });
});

describe('describeReplaceError', () => {
  it('branches on code, never on the error text', () => {
    const id = (code: string) => describeReplaceError(409, 'whatever', code).id;
    expect(id('station_device_changed')).toBe(
      'pages.console.stations.replace.error.changed',
    );
    expect(id('device_in_other_station')).toBe(
      'pages.console.stations.replace.error.otherStation',
    );
    expect(id('device_customer_mismatch')).toBe(
      'pages.console.stations.replace.error.customerMismatch',
    );
    expect(id('device_without_customer')).toBe(
      'pages.console.stations.assign.error.noCustomer',
    );
    expect(describeReplaceError(409, 'station already has a device').id).toBe(
      'pages.console.stations.assign.error.conflict',
    );
  });

  it('puts the current device into the stale-page message', () => {
    expect(
      describeReplaceError(409, 'x', 'station_device_changed', 'ZCT000777')
        .detail,
    ).toBe('ZCT000777');
  });

  it('v1.1 codes tell a vanished station from an unregistered device, and same_device from invalid_request', () => {
    expect(describeReplaceError(404, 'x', 'station_not_found').id).toBe(
      'pages.console.stations.error.notFound',
    );
    expect(describeReplaceError(404, 'x', 'device_not_found').id).toBe(
      'pages.console.stations.replace.error.deviceNotFound',
    );
    expect(describeReplaceError(400, 'x', 'same_device').id).toBe(
      'pages.console.stations.replace.error.sameDevice',
    );
    expect(describeReplaceError(400, 'x', 'invalid_request').id).toBe(
      'pages.console.stations.replace.error.invalid',
    );
  });

  it('404 / 400 without a code (older cloud) fall back to the combined messages', () => {
    expect(describeReplaceError(404).id).toBe(
      'pages.console.stations.replace.error.notFound',
    );
    expect(describeReplaceError(400).id).toBe(
      'pages.console.stations.replace.error.invalid',
    );
  });
});
