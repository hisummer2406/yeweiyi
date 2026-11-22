import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { message } from 'antd';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import zhPages from '@/locales/zh-CN/pages';

const listStations = vi.fn();
const createStation = vi.fn();
const updateStation = vi.fn();
const deleteStation = vi.fn();
const listCustomers = vi.fn();
const listDevices = vi.fn();
let canPlatformAdmin = false;

vi.mock('@umijs/max', () => ({
  useIntl: () => ({
    formatMessage: ({ id }: { id: string }, values?: Record<string, string>) =>
      ((zhPages as Record<string, string>)[id] ?? id).replace(
        /\{(\w+)\}/g,
        (_, k) => values?.[k] ?? '',
      ),
  }),
  useAccess: () => ({ canPlatformAdmin }),
}));
vi.mock('@/services/ant-design-pro/stations', () => ({
  listStations,
  createStation,
  updateStation,
  deleteStation,
}));
vi.mock('@/services/ant-design-pro/customers', () => ({ listCustomers }));
const downloadMiniCode = vi.fn();
vi.mock('@/services/ant-design-pro/devices', () => ({ listDevices }));
vi.mock('../devices/downloadMiniCode', () => ({ downloadMiniCode }));

const { default: StationsTab } = await import('./StationsTab');
const { ApiError } = await import('@/services/ant-design-pro/apiError');

const rows = [
  {
    id: 3,
    customerId: 1,
    name: '一号站',
    address: '某路 1 号',
    createdAt: '2026-10-07T00:00:00Z',
  },
  {
    id: 4,
    customerId: 2,
    name: '二号站',
    address: '',
    createdAt: '2026-10-07T00:00:00Z',
  },
];
const rowOf = async (name: string) =>
  (await screen.findByText(name)).closest('tr') as HTMLElement;

describe('StationsTab', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    canPlatformAdmin = false;
    listStations.mockResolvedValue(rows);
    listDevices.mockResolvedValue([]);
    listCustomers.mockResolvedValue([
      { id: 1, name: '客户甲', createdAt: '' },
      { id: 2, name: '客户乙', createdAt: '' },
    ]);
    vi.spyOn(message, 'success').mockImplementation((() => {}) as never);
    vi.spyOn(message, 'error').mockImplementation((() => {}) as never);
  });
  afterEach(cleanup);

  it('lists stations without a customer column for a customer admin', async () => {
    render(<StationsTab />);
    expect(await screen.findByText('一号站')).toBeTruthy();
    expect(screen.queryByText('所属客户')).toBeNull();
    expect(listCustomers).not.toHaveBeenCalled();
  });

  it('shows the customer name column for a platform admin', async () => {
    canPlatformAdmin = true;
    render(<StationsTab />);
    expect(await screen.findByText('客户甲')).toBeTruthy();
    expect(screen.getAllByText('所属客户').length).toBeGreaterThan(0);
  });

  it('customer admin creates a trimmed station without sending customerId', async () => {
    createStation.mockResolvedValue({ ...rows[0], id: 9 });
    render(<StationsTab />);
    await screen.findByText('一号站');
    fireEvent.click(screen.getByRole('button', { name: /新建油站/ }));
    fireEvent.change(await screen.findByPlaceholderText('油站名称'), {
      target: { value: '  三号站  ' },
    });
    fireEvent.click(screen.getByRole('button', { name: /创\s*建/ }));
    await waitFor(() =>
      expect(createStation).toHaveBeenCalledWith({
        name: '三号站',
        address: '',
      }),
    );
    await waitFor(() => expect(listStations).toHaveBeenCalledTimes(2));
    expect(message.success).toHaveBeenCalledWith('已创建油站 三号站');
  });

  it('platform admin cannot submit without choosing a customer', async () => {
    canPlatformAdmin = true;
    render(<StationsTab />);
    await screen.findByText('一号站');
    fireEvent.click(screen.getByRole('button', { name: /新建油站/ }));
    fireEvent.change(await screen.findByPlaceholderText('油站名称'), {
      target: { value: '四号站' },
    });
    fireEvent.click(screen.getByRole('button', { name: /创\s*建/ }));
    expect(await screen.findByText('请选择客户')).toBeTruthy();
    expect(createStation).not.toHaveBeenCalled();
  });

  it('blank name is rejected without calling the API', async () => {
    render(<StationsTab />);
    await screen.findByText('一号站');
    fireEvent.click(screen.getByRole('button', { name: /新建油站/ }));
    fireEvent.change(await screen.findByPlaceholderText('油站名称'), {
      target: { value: '   ' },
    });
    fireEvent.click(screen.getByRole('button', { name: /创\s*建/ }));
    expect(await screen.findByText('请输入油站名称')).toBeTruthy();
    expect(createStation).not.toHaveBeenCalled();
  });

  it('create 409 shows the duplicate-name message and keeps the dialog open', async () => {
    createStation.mockRejectedValue(new ApiError(409, 'dup'));
    render(<StationsTab />);
    await screen.findByText('一号站');
    fireEvent.click(screen.getByRole('button', { name: /新建油站/ }));
    fireEvent.change(await screen.findByPlaceholderText('油站名称'), {
      target: { value: '一号站' },
    });
    fireEvent.click(screen.getByRole('button', { name: /创\s*建/ }));
    await waitFor(() =>
      expect(message.error).toHaveBeenCalledWith('该客户下已有同名油站。'),
    );
    expect(screen.getByPlaceholderText('油站名称')).toBeTruthy();
  });

  it('stays silent when the auth failure was already handled', async () => {
    createStation.mockRejectedValue(new ApiError(401, undefined, true));
    render(<StationsTab />);
    await screen.findByText('一号站');
    fireEvent.click(screen.getByRole('button', { name: /新建油站/ }));
    fireEvent.change(await screen.findByPlaceholderText('油站名称'), {
      target: { value: 'X' },
    });
    fireEvent.click(screen.getByRole('button', { name: /创\s*建/ }));
    await waitFor(() => expect(createStation).toHaveBeenCalled());
    expect(message.error).not.toHaveBeenCalled();
  });

  it('edit is prefilled and saves name and address by id', async () => {
    updateStation.mockResolvedValue(rows[0]);
    render(<StationsTab />);
    fireEvent.click(within(await rowOf('一号站')).getByText('编辑'));
    const name = (await screen.findByPlaceholderText(
      '油站名称',
    )) as HTMLInputElement;
    expect(name.value).toBe('一号站');
    fireEvent.change(name, { target: { value: ' 一号站（新） ' } });
    fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }));
    await waitFor(() =>
      expect(updateStation).toHaveBeenCalledWith(3, {
        name: '一号站（新）',
        address: '某路 1 号',
      }),
    );
    expect(message.success).toHaveBeenCalledWith('已保存油站 一号站（新）');
  });

  it("editing a second station does not show or save the first one's values", async () => {
    updateStation.mockResolvedValue(rows[1]);
    render(<StationsTab />);
    fireEvent.click(within(await rowOf('一号站')).getByText('编辑'));
    const first = (await screen.findByPlaceholderText(
      '油站名称',
    )) as HTMLInputElement;
    expect(first.value).toBe('一号站');
    fireEvent.click(screen.getByRole('button', { name: /取\s*消/ }));
    await waitFor(() =>
      expect(screen.queryByPlaceholderText('油站名称')).toBeNull(),
    );
    fireEvent.click(within(await rowOf('二号站')).getByText('编辑'));
    const second = (await screen.findByPlaceholderText(
      '油站名称',
    )) as HTMLInputElement;
    expect(second.value).toBe('二号站');
    fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }));
    await waitFor(() =>
      expect(updateStation).toHaveBeenCalledWith(4, {
        name: '二号站',
        address: '',
      }),
    );
  });

  it('delete 409 says the station still has devices', async () => {
    deleteStation.mockRejectedValue(new ApiError(409, 'has devices'));
    render(<StationsTab />);
    fireEvent.click(within(await rowOf('一号站')).getByText('删除'));
    fireEvent.click(await screen.findByRole('button', { name: /确\s*定|OK/ }));
    await waitFor(() => expect(deleteStation).toHaveBeenCalledWith(3));
    await waitFor(() =>
      expect(message.error).toHaveBeenCalledWith(
        '该油站下还有设备，请先把设备移到其他油站，或取消它们的油站。',
      ),
    );
  });

  it('delete success reloads the list', async () => {
    deleteStation.mockResolvedValue(undefined);
    render(<StationsTab />);
    fireEvent.click(within(await rowOf('二号站')).getByText('删除'));
    fireEvent.click(await screen.findByRole('button', { name: /确\s*定|OK/ }));
    await waitFor(() => expect(deleteStation).toHaveBeenCalledWith(4));
    await waitFor(() => expect(listStations).toHaveBeenCalledTimes(2));
    expect(message.success).toHaveBeenCalledWith('已删除油站 二号站');
  });

  describe('current screen column (one station : one device)', () => {
    const dev = (device_name: string, station_id?: number | null) => ({
      device_name,
      station_id,
      is_online: false,
      last_online_at: null,
      last_offline_at: null,
      updated_at: null,
    });

    it('shows the single device code with leading zeros, and 未安装 for an empty station', async () => {
      listDevices.mockResolvedValue([dev('000123', 3), dev('ZCT000002', null)]);
      render(<StationsTab />);
      const r1 = await rowOf('一号站');
      expect(within(r1).getByText('000123')).toBeTruthy();
      const r2 = await rowOf('二号站');
      expect(within(r2).getByText('未安装')).toBeTruthy();
    });

    it('offers a monitoring entry that carries the station only when it has exactly one device', async () => {
      listDevices.mockResolvedValue([dev('000123', 3)]);
      render(<StationsTab />);
      const r1 = await rowOf('一号站');
      const link = within(r1).getByText('查看监控').closest('a');
      expect(link?.getAttribute('href')).toBe(
        '/console/devices/000123?station=3',
      );
      const r2 = await rowOf('二号站');
      expect(within(r2).queryByText('查看监控')).toBeNull();
    });

    it('offers the mini-program code only to platform admins and only for a single device', async () => {
      canPlatformAdmin = true;
      listDevices.mockResolvedValue([dev('000123', 3)]);
      const { unmount } = render(<StationsTab />);
      fireEvent.click(within(await rowOf('一号站')).getByText('下载二维码'));
      expect(downloadMiniCode).toHaveBeenCalledWith(
        '000123',
        expect.any(Function),
      );
      expect(
        within(await rowOf('二号站')).queryByText('下载二维码'),
      ).toBeNull();
      unmount();
      cleanup();
      canPlatformAdmin = false;
      render(<StationsTab />);
      expect(
        within(await rowOf('一号站')).queryByText('下载二维码'),
      ).toBeNull();
    });

    it('flags legacy data with several devices on one station instead of aggregating or picking one', async () => {
      listDevices.mockResolvedValue([dev('A1', 3), dev('A2', 3)]);
      render(<StationsTab />);
      const r1 = await rowOf('一号站');
      expect(within(r1).getByText(/关联了 2 台设备/)).toBeTruthy();
      expect(within(r1).queryByText('A1')).toBeNull();
    });

    it('shows 未知 (not 未安装) when the device list failed', async () => {
      listDevices.mockRejectedValue(new Error('boom'));
      render(<StationsTab />);
      const r1 = await rowOf('一号站');
      expect(within(r1).getByText('未知')).toBeTruthy();
      expect(within(r1).queryByText('未安装')).toBeNull();
    });

    it('shows 未知 when an old cloud returns device rows without station_id', async () => {
      listDevices.mockResolvedValue([dev('A1')]);
      render(<StationsTab />);
      const r1 = await rowOf('一号站');
      expect(within(r1).getByText('未知')).toBeTruthy();
    });
  });
});
