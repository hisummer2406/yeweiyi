import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const push = vi.fn();
const listDevices = vi.fn();
const getLatestTelemetry = vi.fn();

vi.mock('@umijs/max', () => ({ history: { push } }));
vi.mock('@/services/ant-design-pro/devices', () => ({ listDevices }));
vi.mock('@/services/ant-design-pro/telemetry', () => ({ getLatestTelemetry }));

const { default: ConsoleDevices } = await import('./index');

const device = (name: string): API.DeviceRow => ({
  device_name: name,
  is_online: true,
  last_online_at: null,
  last_offline_at: null,
  updated_at: null,
});
const telemetry = (name: string): API.LatestTelemetry => ({
  deviceName: name,
  ts: '2026-10-07T01:02:03Z',
  snapshotSeq: 1,
  tanks: [
    {
      index: 1,
      oilType: '92#',
      oilLevelMm: 100,
      waterLevelMm: 0,
      tempC: 20,
      oilVolumeL: 500,
      waterVolumeL: 0,
      oilWeightKg: 400,
      nullVolumeL: 0,
      oilRatioPct: 10,
      waterRatioPct: 0,
      alarmState: 'ALARM',
      probeConnected: false,
    },
  ],
});

beforeEach(() => vi.clearAllMocks());
afterEach(cleanup);

describe('ConsoleDevices (overview) after extracting TankCard', () => {
  it('still shows the alarm and probe tags on the card', async () => {
    listDevices.mockResolvedValue([device('D1')]);
    getLatestTelemetry.mockResolvedValue(telemetry('D1'));
    render(<ConsoleDevices />);
    expect(await screen.findByText('报警')).toBeTruthy();
    expect(screen.getByText('探针未连接')).toBeTruthy();
  });

  it('still opens the device detail with an escaped name when a card is clicked', async () => {
    listDevices.mockResolvedValue([device('ZCT/01')]);
    getLatestTelemetry.mockResolvedValue(telemetry('ZCT/01'));
    render(<ConsoleDevices />);
    fireEvent.click(await screen.findByText(/1号 92#/));
    expect(push).toHaveBeenCalledWith('/console/devices/ZCT%2F01');
  });

  it('still shows the never-reported state', async () => {
    listDevices.mockResolvedValue([device('D1')]);
    getLatestTelemetry.mockResolvedValue(null);
    render(<ConsoleDevices />);
    expect(await screen.findByText('该设备还没有上报过数据')).toBeTruthy();
  });
});
