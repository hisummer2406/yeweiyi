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

const listCustomers = vi.fn();
const createCustomer = vi.fn();
const renameCustomer = vi.fn();
const deleteCustomer = vi.fn();

vi.mock('@umijs/max', () => ({
  useIntl: () => ({
    formatMessage: ({ id }: { id: string }, values?: Record<string, string>) =>
      ((zhPages as Record<string, string>)[id] ?? id).replace(
        /\{(\w+)\}/g,
        (_, k) => values?.[k] ?? '',
      ),
  }),
}));
vi.mock('@/services/ant-design-pro/customers', () => ({
  listCustomers,
  createCustomer,
  renameCustomer,
  deleteCustomer,
}));

const { default: AdminCustomers } = await import('./index');
const { ApiError } = await import('@/services/ant-design-pro/apiError');

const rows = [
  { id: 1, name: 'default', createdAt: '2026-10-07T00:00:00Z' },
  { id: 2, name: 'A 油站', createdAt: '2026-10-07T00:00:00Z' },
];
const rowOf = async (name: string) =>
  (await screen.findByText(name)).closest('tr') as HTMLElement;

const openModal = async () => {
  render(<AdminCustomers />);
  await screen.findByText('default');
  fireEvent.click(screen.getByRole('button', { name: /新建客户/ }));
  return screen.findByPlaceholderText('新客户名称');
};

describe('AdminCustomers', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listCustomers.mockResolvedValue(rows);
    vi.spyOn(message, 'success').mockImplementation((() => {}) as never);
    vi.spyOn(message, 'error').mockImplementation((() => {}) as never);
  });
  afterEach(cleanup);

  it('lists customers', async () => {
    render(<AdminCustomers />);
    expect(await screen.findByText('default')).toBeTruthy();
  });

  it('creates a trimmed customer name and reloads the list', async () => {
    createCustomer.mockResolvedValue({ id: 2, name: 'A 油站', createdAt: '' });
    const input = await openModal();
    fireEvent.change(input, { target: { value: '  A 油站  ' } });
    fireEvent.click(screen.getByRole('button', { name: /创\s*建/ }));
    await waitFor(() =>
      expect(createCustomer).toHaveBeenCalledWith({ name: 'A 油站' }),
    );
    await waitFor(() => expect(listCustomers).toHaveBeenCalledTimes(2));
    expect(message.success).toHaveBeenCalled();
  });

  it('blank name is rejected without calling the API', async () => {
    const input = await openModal();
    fireEvent.change(input, { target: { value: '   ' } });
    fireEvent.click(screen.getByRole('button', { name: /创\s*建/ }));
    expect(await screen.findByText('请输入客户名称')).toBeTruthy();
    expect(createCustomer).not.toHaveBeenCalled();
  });

  it('409 shows the name-exists message and keeps the dialog open', async () => {
    createCustomer.mockRejectedValue(new ApiError(409, 'exists'));
    const input = await openModal();
    fireEvent.change(input, { target: { value: 'default' } });
    fireEvent.click(screen.getByRole('button', { name: /创\s*建/ }));
    await waitFor(() =>
      expect(message.error).toHaveBeenCalledWith('客户名称已存在'),
    );
    expect(screen.getByPlaceholderText('新客户名称')).toBeTruthy();
  });

  it('stays silent when the auth failure was already handled', async () => {
    createCustomer.mockRejectedValue(new ApiError(401, undefined, true));
    const input = await openModal();
    fireEvent.change(input, { target: { value: 'X' } });
    fireEvent.click(screen.getByRole('button', { name: /创\s*建/ }));
    await waitFor(() => expect(createCustomer).toHaveBeenCalled());
    expect(message.error).not.toHaveBeenCalled();
  });

  it('the built-in default customer has no rename or delete', async () => {
    render(<AdminCustomers />);
    const row = await rowOf('default');
    expect(within(row).queryByText('重命名')).toBeNull();
    expect(within(row).queryByText('删除')).toBeNull();
  });

  it('rename is prefilled, trims, and reloads the list', async () => {
    renameCustomer.mockResolvedValue({ id: 2, name: 'B 油站', createdAt: '' });
    render(<AdminCustomers />);
    fireEvent.click(within(await rowOf('A 油站')).getByText('重命名'));
    const input = (await screen.findByPlaceholderText(
      '新客户名称',
    )) as HTMLInputElement;
    expect(input.value).toBe('A 油站');
    fireEvent.change(input, { target: { value: '  B 油站 ' } });
    fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }));
    await waitFor(() =>
      expect(renameCustomer).toHaveBeenCalledWith(2, 'B 油站'),
    );
    await waitFor(() => expect(listCustomers).toHaveBeenCalledTimes(2));
    expect(message.success).toHaveBeenCalledWith('已重命名为 B 油站');
  });

  it('rename 409 shows name-exists and keeps the dialog open', async () => {
    renameCustomer.mockRejectedValue(new ApiError(409, 'dup'));
    render(<AdminCustomers />);
    fireEvent.click(within(await rowOf('A 油站')).getByText('重命名'));
    const input = await screen.findByPlaceholderText('新客户名称');
    fireEvent.change(input, { target: { value: 'X' } });
    fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }));
    await waitFor(() =>
      expect(message.error).toHaveBeenCalledWith('客户名称已存在'),
    );
    expect(screen.getByPlaceholderText('新客户名称')).toBeTruthy();
  });

  it('delete asks for confirmation first, then deletes and reloads', async () => {
    deleteCustomer.mockResolvedValue(undefined);
    render(<AdminCustomers />);
    fireEvent.click(within(await rowOf('A 油站')).getByText('删除'));
    expect(deleteCustomer).not.toHaveBeenCalled();
    fireEvent.click(await screen.findByRole('button', { name: /确\s*定/ }));
    await waitFor(() => expect(deleteCustomer).toHaveBeenCalledWith(2));
    await waitFor(() => expect(listCustomers).toHaveBeenCalledTimes(2));
    expect(message.success).toHaveBeenCalledWith('已删除客户 A 油站');
  });

  it('delete 409 explains the customer is still in use and does not reload', async () => {
    deleteCustomer.mockRejectedValue(new ApiError(409, 'in use'));
    render(<AdminCustomers />);
    fireEvent.click(within(await rowOf('A 油站')).getByText('删除'));
    fireEvent.click(await screen.findByRole('button', { name: /确\s*定/ }));
    await waitFor(() =>
      expect(message.error).toHaveBeenCalledWith(
        '该客户下还有账号、设备或历史记录，不能删除。',
      ),
    );
    expect(listCustomers).toHaveBeenCalledTimes(1);
  });

  it('delete stays silent when the auth failure was already handled', async () => {
    deleteCustomer.mockRejectedValue(new ApiError(401, undefined, true));
    render(<AdminCustomers />);
    fireEvent.click(within(await rowOf('A 油站')).getByText('删除'));
    fireEvent.click(await screen.findByRole('button', { name: /确\s*定/ }));
    await waitFor(() => expect(deleteCustomer).toHaveBeenCalled());
    expect(message.error).not.toHaveBeenCalled();
  });

  it('a second form submit while the request is pending does not create twice', async () => {
    let resolve: (v: unknown) => void = () => {};
    createCustomer.mockReturnValue(new Promise((r) => (resolve = r)));
    const input = await openModal();
    fireEvent.change(input, { target: { value: 'Z' } });
    const form = input.closest('form') as HTMLElement;
    fireEvent.submit(form);
    await waitFor(() => expect(createCustomer).toHaveBeenCalledTimes(1));
    fireEvent.submit(form);
    fireEvent.submit(form);
    await new Promise((r) => setTimeout(r, 50));
    expect(createCustomer).toHaveBeenCalledTimes(1);
    resolve({ id: 9, name: 'Z', createdAt: '' });
  });
});
