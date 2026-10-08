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
});
