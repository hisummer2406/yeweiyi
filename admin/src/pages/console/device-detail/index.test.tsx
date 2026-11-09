import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import {
  MemoryRouter,
  Route,
  Routes,
  useLocation,
  useNavigate,
} from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import zhPages from '@/locales/zh-CN/pages';

const getLatestTelemetry = vi.fn();
const listAlarms = vi.fn();
const listStockEvents = vi.fn();
const listDevices = vi.fn();
const listCustomers = vi.fn();
const listStations = vi.fn();

// 路由相关的 hook 用真实的 react-router，这样「设备名按 URL 解码」「?tab= 同步」都是真实行为
vi.mock('@umijs/max', async () => {
  const rr =
    await vi.importActual<typeof import('react-router-dom')>(
      'react-router-dom',
    );
  return {
    useParams: rr.useParams,
    useSearchParams: rr.useSearchParams,
    useNavigate: rr.useNavigate,
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
// Keep the module's real helpers (isTelemetryEmpty, telemetryEmptyMessageId); only the request is faked.
vi.mock('@/services/ant-design-pro/telemetry', async (importOriginal) => ({
  ...(await importOriginal<object>()),
  getLatestTelemetry,
}));
vi.mock('@/services/ant-design-pro/alarms', () => ({ listAlarms }));
vi.mock('@/services/ant-design-pro/stock', () => ({ listStockEvents }));
vi.mock('@/services/ant-design-pro/devices', () => ({ listDevices }));
vi.mock('@/services/ant-design-pro/customers', () => ({ listCustomers }));
vi.mock('@/services/ant-design-pro/stations', () => ({ listStations }));

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
  listDevices.mockResolvedValue([]);
  listCustomers.mockResolvedValue([]);
  listStations.mockResolvedValue([]);
});
afterEach(cleanup);

describe('ConsoleDeviceDetail ownership line (#16 G1)', () => {
  const row = (over: Partial<API.DeviceRow>): API.DeviceRow => ({
    device_name: 'D1',
    is_online: true,
    last_online_at: null,
    last_offline_at: null,
    updated_at: null,
    ...over,
  });

  it('shows customer and station next to the device code', async () => {
    listDevices.mockResolvedValue([row({ customer_id: 2, station_id: 5 })]);
    listCustomers.mockResolvedValue([{ id: 2, name: 'A 客户', createdAt: '' }]);
    listStations.mockResolvedValue([
      { id: 5, customerId: 2, name: '五号站', address: '', createdAt: '' },
    ]);
    at('/console/devices/D1');
    expect(await screen.findByText('所属客户：A 客户')).toBeTruthy();
    expect(screen.getByText('所属油站：五号站')).toBeTruthy();
  });

  it('customer admin rows carry no customer_id: no customer shown, customers not requested', async () => {
    listDevices.mockResolvedValue([row({ station_id: null })]);
    at('/console/devices/D1');
    expect(await screen.findByText('所属油站：未分组')).toBeTruthy();
    expect(screen.queryByText(/所属客户/)).toBeNull();
    expect(listCustomers).not.toHaveBeenCalled();
  });

  it('missing station_id (legacy cloud) is unknown, not ungrouped', async () => {
    listDevices.mockResolvedValue([row({ customer_id: null })]);
    at('/console/devices/D1');
    expect(await screen.findByText('所属客户：未分配')).toBeTruthy();
    expect(screen.getByText('所属油站：未知')).toBeTruthy();
  });
});

describe('ConsoleDeviceDetail tabs', () => {
  it('opens on the live data tab by default and does not touch the record APIs', async () => {
    at('/console/devices/D1');
    expect(
      await screen.findByRole('heading', { name: '1号 92#' }),
    ).toBeTruthy();
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
    expect(
      await screen.findByRole('heading', { name: '1号 92#' }),
    ).toBeTruthy();
    expect(listAlarms).not.toHaveBeenCalled();
  });

  it('switching tabs updates ?tab= (replace) and loads only the newly active tab', async () => {
    at('/console/devices/D1');
    await screen.findByRole('heading', { name: '1号 92#' });
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
    await screen.findByRole('heading', { name: '1号 92#' });
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
    getLatestTelemetry.mockResolvedValue({ empty: 'never' });
    at('/console/devices/D1');
    expect(await screen.findByText('该设备还没有上报过数据')).toBeTruthy();
    expect(screen.queryByText('实时数据加载失败')).toBeNull();
  });

  it.each([
    [
      { empty: 'expired', lastReportedAt: '2026-10-01T08:00:00Z' },
      /设备已超过数据保留期未上报，最后上报于 2026-10-01/,
    ],
    [{ empty: 'notProvisioned' }, '设备接入信息尚未就绪，请联系平台管理员检查'],
  ])(
    'an empty state %j says which one, not "never reported" (yeweiyi-cloud#79)',
    async (empty, text) => {
      getLatestTelemetry.mockResolvedValue(empty);
      at('/console/devices/D1');
      expect(await screen.findByText(text)).toBeTruthy();
      expect(screen.queryByText('该设备还没有上报过数据')).toBeNull();
    },
  );

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
    expect(
      await screen.findByRole('heading', { name: '1号 92#' }),
    ).toBeTruthy();
  });
});

describe('switching device in place (e.g. after a screen replacement)', () => {
  const Nav = () => {
    const go = useNavigate();
    return (
      <button type="button" onClick={() => go('/console/devices/D2')}>
        go-d2
      </button>
    );
  };

  it("never shows device A's tanks under device B; B with no data says so; A's late reply is dropped", async () => {
    let resolveA!: (t: API.LatestTelemetry) => void;
    getLatestTelemetry.mockImplementation((name: string) =>
      name === 'D1'
        ? new Promise((r) => {
            resolveA = r;
          })
        : Promise.resolve({ empty: 'never' }),
    );
    render(
      <MemoryRouter initialEntries={['/console/devices/D1']}>
        <Nav />
        <Routes>
          <Route
            path="/console/devices/:deviceName"
            element={<ConsoleDeviceDetail />}
          />
        </Routes>
      </MemoryRouter>,
    );
    await waitFor(() => expect(getLatestTelemetry).toHaveBeenCalledWith('D1'));
    fireEvent.click(screen.getByText('go-d2'));
    // 新设备从未上报：明确显示无数据
    expect(await screen.findByText('该设备还没有上报过数据')).toBeTruthy();
    expect(getLatestTelemetry).toHaveBeenCalledWith('D2');
    // 旧设备 A 的响应迟到，不能写进 B 的页面
    resolveA(telemetry);
    await new Promise((r) => setTimeout(r, 30));
    expect(screen.queryByRole('heading', { name: '1号 92#' })).toBeNull();
    expect(screen.getByText('该设备还没有上报过数据')).toBeTruthy();
  });
});

describe('station entry (?station=) follows a screen replacement (contract §8.1)', () => {
  const row = (device_name: string, station_id: number | null) => ({
    device_name,
    station_id,
    is_online: false,
    last_online_at: null,
    last_offline_at: null,
    updated_at: null,
  });

  it('opened for A but the station now holds B: switches to B, keeps tab/station, never queries A afterwards', async () => {
    listDevices.mockResolvedValue([row('A', null), row('B', 5)]);
    getLatestTelemetry.mockImplementation((n: string) =>
      Promise.resolve(n === 'B' ? { empty: 'never' } : telemetry),
    );
    at('/console/devices/A?station=5&tab=snapshot');
    await waitFor(() =>
      expect(screen.getByTestId('where').textContent).toBe(
        '/console/devices/B?station=5&tab=snapshot',
      ),
    );
    // B 尚无数据：空状态，不显示 A 的罐
    expect(await screen.findByText('该设备还没有上报过数据')).toBeTruthy();
    expect(screen.queryByRole('heading', { name: '1号 92#' })).toBeNull();
  });

  it('without ?station= (independent entry for A) it stays on A', async () => {
    listDevices.mockResolvedValue([row('A', null), row('B', 5)]);
    at('/console/devices/A');
    expect(
      await screen.findByRole('heading', { name: '1号 92#' }),
    ).toBeTruthy();
    // 只有归属行的一次查询；没有 ?station= 时不为「跟随换机」轮询设备列表
    await new Promise((r) => setTimeout(r, 30));
    expect(listDevices).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('where').textContent).toBe('/console/devices/A');
  });

  it('station with several devices (legacy data) or none does not switch', async () => {
    listDevices.mockResolvedValue([row('A', 5), row('B', 5)]);
    at('/console/devices/A?station=5');
    expect(
      await screen.findByRole('heading', { name: '1号 92#' }),
    ).toBeTruthy();
    await new Promise((r) => setTimeout(r, 30));
    expect(screen.getByTestId('where').textContent).toBe(
      '/console/devices/A?station=5',
    );
  });
});
