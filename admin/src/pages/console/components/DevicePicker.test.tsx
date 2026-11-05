import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import zhPages from '@/locales/zh-CN/pages';

const listDevices = vi.fn();
const listStations = vi.fn();
let params = new URLSearchParams();
const setParams = vi.fn((next: URLSearchParams, _opts?: unknown) => {
  params = new URLSearchParams(next);
});

vi.mock('@umijs/max', () => ({
  useIntl: () => ({
    formatMessage: ({ id }: { id: string }, values?: Record<string, string>) =>
      ((zhPages as Record<string, string>)[id] ?? id).replace(
        /\{(\w+)\}/g,
        (_, k) => values?.[k] ?? '',
      ),
  }),
  useSearchParams: () => [params, setParams],
  useAccess: () => ({ canPlatformAdmin: false }),
}));
vi.mock('@/services/ant-design-pro/devices', () => ({ listDevices }));
vi.mock('@/services/ant-design-pro/stations', () => ({ listStations }));
vi.mock('@/services/ant-design-pro/customers', () => ({
  listCustomers: vi.fn().mockResolvedValue([]),
}));

const { default: DevicePicker } = await import('./DevicePicker');

const dev = (name: string): API.DeviceRow => ({
  device_name: name,
  is_online: true,
  last_online_at: null,
  last_offline_at: null,
  updated_at: null,
});
const child = vi.fn((d: string) => <div>records-of-{d}</div>);

beforeEach(() => {
  vi.clearAllMocks();
  params = new URLSearchParams();
  listDevices.mockResolvedValue([dev('D1'), dev('D2')]);
  listStations.mockResolvedValue([]);
});
afterEach(cleanup);

describe('DevicePicker', () => {
  it('renders the records for the device named in ?device=', async () => {
    params = new URLSearchParams('device=D1');
    render(<DevicePicker>{child}</DevicePicker>);
    expect(await screen.findByText('records-of-D1')).toBeTruthy();
  });

  it('does not render or request records until a device is chosen', async () => {
    render(<DevicePicker>{child}</DevicePicker>);
    expect(await screen.findByText('请先选择设备')).toBeTruthy();
    expect(child).not.toHaveBeenCalled();
  });

  it('shows "no devices bound" and renders no records for an empty list', async () => {
    listDevices.mockResolvedValue([]);
    render(<DevicePicker>{child}</DevicePicker>);
    expect(await screen.findByText('还没有绑定设备')).toBeTruthy();
    expect(child).not.toHaveBeenCalled();
  });

  it('does not render records for a device the user cannot see, and says so', async () => {
    params = new URLSearchParams('device=SECRET');
    render(<DevicePicker>{child}</DevicePicker>);
    expect(await screen.findByText(/设备 SECRET 不可见或不存在/)).toBeTruthy();
    expect(child).not.toHaveBeenCalled();
  });

  it('a failed device list shows an error with retry, not "no devices"', async () => {
    listDevices.mockRejectedValueOnce(new Error('boom'));
    render(<DevicePicker>{child}</DevicePicker>);
    expect(await screen.findByText('设备列表加载失败')).toBeTruthy();
    expect(screen.queryByText('还没有绑定设备')).toBeNull();
    params = new URLSearchParams('device=D2');
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(await screen.findByText('records-of-D2')).toBeTruthy();
  });

  it('choosing a device writes it to ?device= (replace, keeping other params)', async () => {
    params = new URLSearchParams('tab=alarms');
    render(<DevicePicker>{child}</DevicePicker>);
    await screen.findByText('请先选择设备');
    fireEvent.mouseDown(screen.getByRole('combobox'));
    // antd 下拉里同一个选项会渲染两份（无障碍列表 + 可点项），点最后一个
    const options = await screen.findAllByText('D2');
    fireEvent.click(options[options.length - 1]);
    await waitFor(() => expect(setParams.mock.calls.length).toBeGreaterThan(1));
    const [next, opts] = setParams.mock.calls[setParams.mock.calls.length - 1];
    expect(next.get('device')).toBe('D2');
    expect(next.get('tab')).toBe('alarms');
    expect(opts).toEqual({ replace: true });
  });

  it('with no ?device= it defaults to the first device so the records show up', async () => {
    params = new URLSearchParams('tab=alarms');
    render(<DevicePicker>{child}</DevicePicker>);
    await waitFor(() => expect(setParams).toHaveBeenCalled());
    const [next, opts] = setParams.mock.calls[0];
    expect(next.get('device')).toBe('D1');
    expect(next.get('tab')).toBe('alarms');
    expect(opts).toEqual({ replace: true });
  });
});

describe('DevicePicker station filter', () => {
  const withStation = (name: string, station_id: number | null) => ({
    ...dev(name),
    station_id,
  });
  const stations = [
    { id: 1, customerId: 7, name: '甲站', address: '', createdAt: '' },
    { id: 2, customerId: 7, name: '乙站', address: '', createdAt: '' },
  ];
  beforeEach(() => {
    listStations.mockResolvedValue(stations);
    listDevices.mockResolvedValue([
      withStation('D1', 1),
      withStation('D2', 2),
      withStation('D3', null),
    ]);
  });

  it('with a station chosen the device comes from the station and the device dropdown is hidden', async () => {
    params = new URLSearchParams('station=2');
    render(<DevicePicker>{child}</DevicePicker>);
    expect(await screen.findByText('records-of-D2')).toBeTruthy();
    expect(screen.getAllByRole('combobox')).toHaveLength(1);
    expect(screen.getByText('当前大屏：D2')).toBeTruthy();
  });

  it('a station without a device shows a hint and requests nothing', async () => {
    listDevices.mockResolvedValue([
      withStation('D1', 1),
      withStation('D3', null),
    ]);
    params = new URLSearchParams('station=2');
    render(<DevicePicker>{child}</DevicePicker>);
    expect(await screen.findByText('该油站还没有安装大屏')).toBeTruthy();
    expect(child).not.toHaveBeenCalled();
  });

  it('writes the station into ?station= while keeping ?device=', async () => {
    params = new URLSearchParams('device=D1');
    render(<DevicePicker>{child}</DevicePicker>);
    const combos = await screen.findAllByRole('combobox');
    fireEvent.mouseDown(combos[0]);
    fireEvent.click(await screen.findByTitle('乙站'));
    const next = setParams.mock.calls[0][0] as URLSearchParams;
    expect(next.get('station')).toBe('2');
  });

  it('has no station filter when the stations endpoint fails', async () => {
    listStations.mockRejectedValue(new Error('404'));
    render(<DevicePicker>{child}</DevicePicker>);
    await screen.findByText('请先选择设备');
    expect(screen.getAllByRole('combobox')).toHaveLength(1);
  });

  describe('station entry follows a screen replacement (contract §8.1)', () => {
    const inStation = (name: string, station_id: number | null) => ({
      ...dev(name),
      station_id,
    });
    const stations = [
      { id: 5, customerId: 1, name: '五号站', address: '', createdAt: '' },
    ];

    it('station 5 changes from A to B while A is selected: switches ?device= to B', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      try {
        listStations.mockResolvedValue(stations);
        listDevices.mockResolvedValue([
          inStation('A', 5),
          inStation('B', null),
        ]);
        params = new URLSearchParams('station=5&device=A');
        render(<DevicePicker>{child}</DevicePicker>);
        expect(await screen.findByText('records-of-A')).toBeTruthy();
        // 换机：A 解除，B 进站（下一次轮询得知）
        listDevices.mockResolvedValue([
          inStation('A', null),
          inStation('B', 5),
        ]);
        await vi.advanceTimersByTimeAsync(30_000);
        await waitFor(() => expect(params.get('device')).toBe('B'));
        expect(params.get('station')).toBe('5');
      } finally {
        vi.useRealTimers();
      }
    });

    it('a slow poll that resolves after a newer one is discarded', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      try {
        params = new URLSearchParams('device=D2');
        render(<DevicePicker>{child}</DevicePicker>);
        expect(await screen.findByText('records-of-D2')).toBeTruthy();
        let releaseSlow: (rows: API.DeviceRow[]) => void = () => {};
        listDevices.mockImplementationOnce(
          () => new Promise<API.DeviceRow[]>((r) => (releaseSlow = r)),
        );
        await vi.advanceTimersByTimeAsync(30_000); // 慢请求发出
        listDevices.mockResolvedValueOnce([dev('D1'), dev('D2')]);
        await vi.advanceTimersByTimeAsync(30_000); // 较新的请求先返回
        releaseSlow([dev('D1')]); // 迟到的旧快照里已没有 D2
        await new Promise((r) => setTimeout(r, 0));
        expect(screen.getByText('records-of-D2')).toBeTruthy();
        expect(screen.queryByText(/设备 D2 不可见或不存在/)).toBeNull();
      } finally {
        vi.useRealTimers();
      }
    });

    it('a station keeps following its current device even if ?device= names another', async () => {
      listStations.mockResolvedValue(stations);
      listDevices.mockResolvedValue([inStation('A', 5), inStation('C', null)]);
      params = new URLSearchParams('station=5&device=C');
      render(<DevicePicker>{child}</DevicePicker>);
      expect(await screen.findByText('records-of-A')).toBeTruthy();
      await waitFor(() => expect(params.get('device')).toBe('A'));
    });
  });
});
