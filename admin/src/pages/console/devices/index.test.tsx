import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import zhPages from '@/locales/zh-CN/pages';

const push = vi.fn();
const listDevices = vi.fn();
const getLatestTelemetry = vi.fn();
const listStations = vi.fn();
const listCustomers = vi.fn();
let initialSearch = '';
let canPlatformAdmin = false;

vi.mock('@umijs/max', () => ({
  history: { push },
  useAccess: () => ({ canPlatformAdmin }),
  useIntl: () => ({
    formatMessage: ({ id }: { id: string }) =>
      (zhPages as Record<string, string>)[id] ?? id,
  }),
  // 带状态的 useSearchParams：setParams 后重新渲染，和 URL 同步的行为一致
  useSearchParams: () => {
    const [p, setP] = useState(() => new URLSearchParams(initialSearch));
    return [p, (next: URLSearchParams) => setP(new URLSearchParams(next))];
  },
}));
vi.mock('@/services/ant-design-pro/devices', () => ({ listDevices }));
vi.mock('@/services/ant-design-pro/stations', () => ({ listStations }));
vi.mock('@/services/ant-design-pro/customers', () => ({ listCustomers }));
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

beforeEach(() => {
  vi.clearAllMocks();
  initialSearch = '';
  canPlatformAdmin = false;
  listStations.mockResolvedValue([]);
  listCustomers.mockResolvedValue([]);
});
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
    expect(push).toHaveBeenCalledWith('/console/devices/ZCT%2F01?tank=1');
  });

  it('still shows the never-reported state', async () => {
    listDevices.mockResolvedValue([device('D1')]);
    getLatestTelemetry.mockResolvedValue(null);
    render(<ConsoleDevices />);
    expect(await screen.findByText('该设备还没有上报过数据')).toBeTruthy();
  });
});

describe('ConsoleDevices (overview) station filter', () => {
  const dev = (name: string, station_id?: number | null): API.DeviceRow => ({
    ...device(name),
    station_id,
  });
  const stations = [
    { id: 1, customerId: 7, name: '甲站', address: '', createdAt: '' },
    { id: 2, customerId: 7, name: '乙站', address: '', createdAt: '' },
  ];
  const filter = () =>
    screen.findByRole('combobox', {
      name: zhPages['pages.console.stationFilter.label'],
    });
  const choose = async (label: string) => {
    fireEvent.mouseDown(await filter());
    fireEvent.click(await screen.findByTitle(label));
  };

  beforeEach(() => {
    listStations.mockResolvedValue(stations);
    listDevices.mockResolvedValue([
      dev('D-A', 1),
      dev('D-B', 2),
      dev('D-C', null),
    ]);
    getLatestTelemetry.mockImplementation((n: string) =>
      Promise.resolve(telemetry(n)),
    );
  });

  it('titles each card with its station and orders ungrouped last, with no section headings', async () => {
    listDevices.mockResolvedValue([
      dev('D-C', null),
      dev('D-B', 2),
      dev('D-A', 1),
    ]);
    render(<ConsoleDevices />);
    await filter();
    expect(await screen.findByText('甲站')).toBeTruthy();
    expect(screen.queryByRole('heading', { level: 5 })).toBeNull();
    const text = document.body.textContent ?? '';
    expect(text.indexOf('甲站')).toBeLessThan(text.indexOf('乙站'));
    expect(text.indexOf('乙站')).toBeLessThan(text.indexOf('D-C'));
    expect(screen.getByText('D-A')).toBeTruthy();
  });

  it('filters to one station without sections, and does not re-request telemetry', async () => {
    render(<ConsoleDevices />);
    await screen.findByText('D-B');
    const calls = getLatestTelemetry.mock.calls.length;
    await choose('乙站');
    await waitFor(() => expect(screen.queryByText('D-A')).toBeNull());
    expect(screen.getByText('D-B')).toBeTruthy();
    expect(screen.queryByRole('heading', { level: 5 })).toBeNull();
    expect(getLatestTelemetry.mock.calls.length).toBe(calls);
  });

  it('filters to ungrouped devices', async () => {
    render(<ConsoleDevices />);
    await screen.findByText('D-B');
    await choose(zhPages['pages.console.stationFilter.none']);
    await waitFor(() => expect(screen.queryByText('D-A')).toBeNull());
    expect(screen.getByText('D-C')).toBeTruthy();
  });

  it('opens straight to ?station=<id>', async () => {
    initialSearch = 'station=1';
    render(<ConsoleDevices />);
    expect(await screen.findByText('D-A')).toBeTruthy();
    await waitFor(() => expect(screen.queryByText('D-B')).toBeNull());
  });

  it('falls back to all stations when ?station= is not visible', async () => {
    initialSearch = 'station=99';
    render(<ConsoleDevices />);
    expect(await screen.findByText('D-A')).toBeTruthy();
    expect(screen.getByText('D-B')).toBeTruthy();
  });

  it('shows no filter and no sections when the cloud has no stations endpoint', async () => {
    listStations.mockRejectedValue(new Error('404'));
    render(<ConsoleDevices />);
    expect(await screen.findByText('D-A')).toBeTruthy();
    expect(screen.queryByRole('combobox')).toBeNull();
    expect(screen.queryByRole('heading', { level: 5 })).toBeNull();
  });

  it('shows no filter when no row carries station_id (old cloud)', async () => {
    listDevices.mockResolvedValue([dev('D-A'), dev('D-B')]);
    render(<ConsoleDevices />);
    expect(await screen.findByText('D-A')).toBeTruthy();
    expect(screen.queryByRole('combobox')).toBeNull();
  });

  it('shows no filter when there are no stations', async () => {
    listStations.mockResolvedValue([]);
    render(<ConsoleDevices />);
    expect(await screen.findByText('D-A')).toBeTruthy();
    expect(screen.queryByRole('combobox')).toBeNull();
  });

  it('labels a station #id when its device points at one the caller cannot see', async () => {
    listDevices.mockResolvedValue([dev('D-A', 1), dev('D-X', 77)]);
    render(<ConsoleDevices />);
    expect(await screen.findByText('#77')).toBeTruthy();
    expect(screen.getByText('甲站')).toBeTruthy();
  });

  it('appends the customer name to station titles for a platform admin only', async () => {
    canPlatformAdmin = true;
    listCustomers.mockResolvedValue([{ id: 7, name: '客户七', createdAt: '' }]);
    render(<ConsoleDevices />);
    expect(
      (await screen.findAllByText('甲站（客户七）')).length,
    ).toBeGreaterThan(0);
  });

  it('keeps the plain station name for a customer admin and never asks for customers', async () => {
    render(<ConsoleDevices />);
    await screen.findByText('甲站');
    expect(listCustomers).not.toHaveBeenCalled();
  });
});

describe('ConsoleDevices (overview) paging and request control', () => {
  const many = (n: number, station?: (i: number) => number | null) =>
    Array.from({ length: n }, (_, i) => ({
      ...device(`D${String(i + 1).padStart(3, '0')}`),
      ...(station ? { station_id: station(i) } : {}),
    }));

  beforeEach(() => {
    getLatestTelemetry.mockImplementation((n: string) =>
      Promise.resolve(telemetry(n)),
    );
  });

  it('renders and requests telemetry for the first page only', async () => {
    listDevices.mockResolvedValue(many(30));
    render(<ConsoleDevices />);
    expect(await screen.findByText('D012')).toBeTruthy();
    expect(screen.queryByText('D013')).toBeNull();
    await waitFor(() => expect(getLatestTelemetry).toHaveBeenCalledTimes(12));
    const asked = getLatestTelemetry.mock.calls.map((c) => c[0]);
    expect(asked).not.toContain('D013');
  });

  it('fetches telemetry for a page only when it is opened', async () => {
    listDevices.mockResolvedValue(many(30));
    render(<ConsoleDevices />);
    await screen.findByText('D012');
    fireEvent.click(screen.getByTitle('2'));
    expect(await screen.findByText('D013')).toBeTruthy();
    expect(screen.queryByText('D001')).toBeNull();
    await waitFor(() =>
      expect(getLatestTelemetry).toHaveBeenCalledWith('D024'),
    );
    expect(getLatestTelemetry).not.toHaveBeenCalledWith('D025');
  });

  it('shows no pager for a single page of devices', async () => {
    listDevices.mockResolvedValue(many(3));
    render(<ConsoleDevices />);
    await screen.findByText('D003');
    expect(screen.queryByTitle('2')).toBeNull();
  });

  it('keeps at most 5 telemetry requests in flight', async () => {
    let running = 0;
    let peak = 0;
    getLatestTelemetry.mockImplementation(async (n: string) => {
      running++;
      peak = Math.max(peak, running);
      await new Promise((r) => setTimeout(r, 10));
      running--;
      return telemetry(n);
    });
    listDevices.mockResolvedValue(many(12));
    render(<ConsoleDevices />);
    await waitFor(() => expect(getLatestTelemetry).toHaveBeenCalledTimes(12));
    await waitFor(() => expect(running).toBe(0));
    expect(peak).toBeLessThanOrEqual(5);
    expect(peak).toBeGreaterThan(1);
  });

  it('returns to page 1 when the station filter changes', async () => {
    listStations.mockResolvedValue([
      { id: 1, customerId: 7, name: '甲站', address: '', createdAt: '' },
    ]);
    listDevices.mockResolvedValue(many(30, () => 1));
    render(<ConsoleDevices />);
    await screen.findByText('D012');
    fireEvent.click(screen.getByTitle('3'));
    await screen.findByText('D025');
    fireEvent.mouseDown(
      await screen.findByRole('combobox', {
        name: zhPages['pages.console.stationFilter.label'],
      }),
    );
    fireEvent.click(await screen.findByTitle('甲站'));
    expect(await screen.findByText('D001')).toBeTruthy();
  });

  describe('polling', () => {
    afterEach(() => {
      Object.defineProperty(document, 'hidden', {
        configurable: true,
        value: false,
      });
      vi.useRealTimers();
    });

    const setHidden = (hidden: boolean) =>
      Object.defineProperty(document, 'hidden', {
        configurable: true,
        value: hidden,
      });

    it('pauses while the tab is hidden and refreshes when it comes back', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      listDevices.mockResolvedValue(many(2));
      render(<ConsoleDevices />);
      await screen.findByText('D002');
      expect(listDevices).toHaveBeenCalledTimes(1);

      setHidden(true);
      await vi.advanceTimersByTimeAsync(90_000);
      expect(listDevices).toHaveBeenCalledTimes(1);

      setHidden(false);
      document.dispatchEvent(new Event('visibilitychange'));
      await waitFor(() => expect(listDevices).toHaveBeenCalledTimes(2));
    });

    it('keeps polling while the tab is visible', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      listDevices.mockResolvedValue(many(2));
      render(<ConsoleDevices />);
      await screen.findByText('D002');
      await vi.advanceTimersByTimeAsync(30_000);
      await waitFor(() => expect(listDevices).toHaveBeenCalledTimes(2));
    });
  });
});
