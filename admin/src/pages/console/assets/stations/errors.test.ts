import { describe, expect, it } from 'vitest';
import {
  describeAssignStationError,
  describeDeleteStationError,
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
      detail: 'bad name',
    });
    expect(describeStationError(500, 'boom')).toEqual({
      id: 'pages.console.stations.error.unknown',
      detail: 'boom',
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
      'pages.console.stations.assign.error.noCustomer',
    );
    expect(describeAssignStationError(404).id).toBe(
      'pages.console.stations.assign.error.notFound',
    );
    expect(describeAssignStationError(403).id).toBe(
      'pages.console.stations.error.forbidden',
    );
  });
});
