import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import zhPages from '@/locales/zh-CN/pages';

const getLatestTelemetry = vi.fn();
const listAlarms = vi.fn();
const listStockEvents = vi.fn();

// 路由相关的 hook 用真实的 react-router，这样「设备名按 URL 解码」「?tab= 同步」都是真实行为
vi.mock('@umijs/max', async () => {
  const rr =
    await vi.importActual<typeof import('react-router-dom')>(
      'react-router-dom',
    );
  return {
    useParams: rr.useParams,
    useSearchParams: rr.useSearchParams,
    useIntl: () => ({
      formatMessage: (
        { id }: { id: string },
        values?: Record<string, string>,
      ) =>
        ((zhPages as Record<string, string>)[id] ?? id).replace(
          /\{(\w+)\}/g,
          (_, k) => values?.[k] ?? '',
        ),
    }),
  };
});
vi.mock('@/services/ant-design-pro/telemetry', () => ({ getLatestTelemetry }));
vi.mock('@/services/ant-design-pro/alarms', () => ({ listAlarms }));
vi.mock('@/services/ant-design-pro/stock', () => ({ listStockEvents }));

const { default: ConsoleDeviceDetail } = await import('./index');

const tank: API.Tank = {
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
  alarmState: 'NORMAL',
  probeConnected: true,
};
const telemetry: API.LatestTelemetry = {
  deviceName: 'D1',
  ts: '2026-10-07T01:02:03Z',
  snapshotSeq: 1,
  tanks: [tank],
};
const emptyStock = {
  rows: [],
  parsed: 0,
  unparsable: 0,
  legacySkipped: 0,
  invalidSource: 0,
  folded: 0,
  truncated: false,
  partial: false,
};

const Where = () => {
  const l = useLocation();
  return <div data-testid="where">{`${l.pathname}${l.search}`}</div>;
};
const at = (url: string) =>
  render(
    <MemoryRouter initialEntries={[url]}>
      <Where />
      <Routes>
        <Route
          path="/console/devices/:deviceName"
          element={<ConsoleDeviceDetail />}
        />
      </Routes>
    </MemoryRouter>,
  );

beforeEach(() => {
  vi.clearAllMocks();
  getLatestTelemetry.mockResolvedValue(telemetry);
  listAlarms.mockResolvedValue({ rows: [], partial: false });
  listStockEvents.mockResolvedValue(emptyStock);
});
afterEach(cleanup);

describe('ConsoleDeviceDetail tabs', () => {
  it('opens on the live data tab by default and does not touch the record APIs', async () => {
    at('/console/devices/D1');
    expect(await screen.findByText(/1号 92#/)).toBeTruthy();
    expect(getLatestTelemetry).toHaveBeenCalledWith('D1');
    expect(listAlarms).not.toHaveBeenCalled();
    expect(listStockEvents).not.toHaveBeenCalled();
  });

  it('?tab=alarms opens the alarms tab for that device', async () => {
    at('/console/devices/D1?tab=alarms');
    await screen.findByText('该时间范围内没有报警');
    expect(listAlarms.mock.calls[0][0].device).toBe('D1');
    expect(getLatestTelemetry).not.toHaveBeenCalled();
    expect(listStockEvents).not.toHaveBeenCalled();
  });

  it('?tab=stock opens the stock tab showing both directions', async () => {
    listStockEvents.mockResolvedValue({
      ...emptyStock,
      rows: [
        {
          eventId: 'i',
          tank: 1,
          direction: 'IN',
          oilType: 'IN-OIL',
          source: 'AUTO',
        },
        {
          eventId: 'o',
          tank: 1,
          direction: 'OUT',
          oilType: 'OUT-OIL',
          source: 'AUTO',
        },
      ],
    });
    at('/console/devices/D1?tab=stock');
    await screen.findByText('IN-OIL');
    expect(screen.getByText('OUT-OIL')).toBeTruthy();
    expect(listStockEvents.mock.calls[0][0].device).toBe('D1');
  });

  it('an invalid tab falls back to live data', async () => {
    at('/console/devices/D1?tab=foo');
    expect(await screen.findByText(/1号 92#/)).toBeTruthy();
    expect(listAlarms).not.toHaveBeenCalled();
  });

  it('switching tabs updates ?tab= (replace) and loads only the newly active tab', async () => {
    at('/console/devices/D1');
    await screen.findByText(/1号 92#/);
    fireEvent.click(screen.getByRole('tab', { name: '报警记录' }));
    await screen.findByText('该时间范围内没有报警');
    expect(screen.getByTestId('where').textContent).toBe(
      '/console/devices/D1?tab=alarms',
    );
    expect(listStockEvents).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('tab', { name: '出入库记录' }));
    await screen.findByText('该时间范围内没有出入库记录');
    expect(screen.getByTestId('where').textContent).toBe(
      '/console/devices/D1?tab=stock',
    );
  });

  it('leaving a tab unmounts it, so it stops polling in the background', async () => {
    at('/console/devices/D1?tab=alarms');
    await screen.findByText('该时间范围内没有报警');
    fireEvent.click(screen.getByRole('tab', { name: '实时数据' }));
    await screen.findByText(/1号 92#/);
    expect(screen.queryByText('该时间范围内没有报警')).toBeNull();
  });

  it('a device name with special characters is decoded once and used as is', async () => {
    at('/console/devices/ZCT%2F01?tab=alarms');
    await screen.findByText('该时间范围内没有报警');
    expect(listAlarms.mock.calls[0][0].device).toBe('ZCT/01');
    expect(screen.getByText(/设备详情：ZCT\/01/)).toBeTruthy();
  });

  it('a device name that contains a percent sign is not decoded twice', async () => {
    at('/console/devices/A%2541?tab=alarms');
    await screen.findByText('该时间范围内没有报警');
    expect(listAlarms.mock.calls[0][0].device).toBe('A%41');
  });

  it('the device name is shown in the title', async () => {
    at('/console/devices/D1');
    expect(await screen.findByText('设备详情：D1')).toBeTruthy();
  });
});

describe('live data tab states', () => {
  it('shows the report time', async () => {
    at('/console/devices/D1');
    expect(await screen.findByText(/上报时间 2026-/)).toBeTruthy();
  });

  it('404 (never reported) is a normal state, not an error', async () => {
    getLatestTelemetry.mockResolvedValue(null);
    at('/console/devices/D1');
    expect(await screen.findByText('该设备还没有上报过数据')).toBeTruthy();
    expect(screen.queryByText('实时数据加载失败')).toBeNull();
  });

  it('403 says the device may not be viewed, not "never reported"', async () => {
    getLatestTelemetry.mockRejectedValue({
      response: { status: 403, data: { error: 'forbidden' } },
    });
    at('/console/devices/D1');
    expect(await screen.findByText('无权查看该设备')).toBeTruthy();
    expect(screen.queryByText('该设备还没有上报过数据')).toBeNull();
  });

  it('a revoked account (403 "not authorized") and a 401 stay silent', async () => {
    for (const rejection of [
      { response: { status: 403, data: { error: 'not authorized' } } },
      { response: { status: 401, data: {} } },
    ]) {
      cleanup();
      getLatestTelemetry.mockRejectedValue(rejection);
      at('/console/devices/D1');
      await waitFor(() => expect(getLatestTelemetry).toHaveBeenCalled());
      await new Promise((r) => setTimeout(r, 30));
      expect(screen.queryByText('无权查看该设备')).toBeNull();
      expect(screen.queryByText('实时数据加载失败')).toBeNull();
    }
  });

  it('a failure shows an error with retry', async () => {
    getLatestTelemetry.mockRejectedValueOnce({
      response: { status: 500, data: {} },
    });
    at('/console/devices/D1');
    expect(await screen.findByText('实时数据加载失败')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(await screen.findByText(/1号 92#/)).toBeTruthy();
  });
});
