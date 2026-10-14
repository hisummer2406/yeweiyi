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

const listUsers = vi.fn();
const updateUser = vi.fn();
const listCustomers = vi.fn();

vi.mock('@umijs/max', () => ({
  useIntl: () => ({
    formatMessage: ({ id }: { id: string }, values?: Record<string, string>) =>
      ((zhPages as Record<string, string>)[id] ?? id).replace(
        /\{(\w+)\}/g,
        (_, k) => values?.[k] ?? '',
      ),
  }),
  useModel: () => ({ initialState: { currentUser: { userId: 1 } } }),
}));
vi.mock('@/services/ant-design-pro/users', () => ({
  listUsers,
  updateUser,
  createUser: vi.fn(),
  moveUser: vi.fn(),
}));
vi.mock('@/services/ant-design-pro/customers', () => ({ listCustomers }));

const { default: AdminUsers } = await import('./index');

const users = [
  {
    id: 1,
    username: 'root',
    phone: null,
    role: 'platform_admin',
    customerId: null,
    disabled: false,
    createdAt: '2026-10-07T00:00:00Z',
  },
  {
    id: 2,
    username: null,
    phone: '13800138000',
    role: 'customer_admin',
    customerId: 5,
    disabled: false,
    createdAt: '2026-10-07T00:00:00Z',
  },
  {
    id: 3,
    username: null,
    phone: '13900139000',
    role: 'customer_user',
    customerId: 5,
    disabled: true,
    createdAt: '2026-10-07T00:00:00Z',
  },
];

const rowOf = async (name: string) =>
  (await screen.findByText(name)).closest('tr') as HTMLElement;

beforeEach(() => {
  vi.clearAllMocks();
  listUsers.mockResolvedValue(users);
  listCustomers.mockResolvedValue([{ id: 5, name: 'A 油站', createdAt: '' }]);
  vi.spyOn(message, 'success').mockImplementation((() => {}) as never);
  vi.spyOn(message, 'error').mockImplementation((() => {}) as never);
});
afterEach(cleanup);

describe('AdminUsers', () => {
  it('shows role labels and the customer name', async () => {
    render(<AdminUsers />);
    const row = await rowOf('13800138000');
    expect(within(row).getByText('客户管理员')).toBeTruthy();
    expect(within(row).getByText('A 油站')).toBeTruthy();
  });

  it('falls back to #id when the customer list cannot load', async () => {
    listCustomers.mockRejectedValue(new Error('boom'));
    render(<AdminUsers />);
    expect(within(await rowOf('13800138000')).getByText('#5')).toBeTruthy();
  });

  it('the signed-in user cannot disable themselves; platform admins cannot be moved', async () => {
    render(<AdminUsers />);
    const self = await rowOf('root');
    expect(within(self).queryByText('停用')).toBeNull();
    expect(within(self).queryByText('启用')).toBeNull();
    expect(within(self).queryByText('换客户')).toBeNull();
    expect(within(self).getByText('重置密码')).toBeTruthy();
  });

  it('staff have no password to reset; admins do', async () => {
    render(<AdminUsers />);
    const staff = await rowOf('13900139000');
    expect(within(staff).queryByText('重置密码')).toBeNull();
    const admin = await rowOf('13800138000');
    expect(within(admin).getByText('重置密码')).toBeTruthy();
  });

  it('customer-side accounts are shown by phone, never by a username', async () => {
    render(<AdminUsers />);
    const row = await rowOf('13800138000');
    expect(within(row).getByText('13800138000')).toBeTruthy();
    expect(screen.queryByText('null')).toBeNull();
  });

  it('disabling asks for confirmation, then calls updateUser', async () => {
    updateUser.mockResolvedValue({});
    render(<AdminUsers />);
    fireEvent.click(within(await rowOf('13800138000')).getByText('停用'));
    expect(updateUser).not.toHaveBeenCalled();
    fireEvent.click(await screen.findByRole('button', { name: /确\s*定/ }));
    await waitFor(() =>
      expect(updateUser).toHaveBeenCalledWith(2, { disabled: true }),
    );
    await waitFor(() => expect(listUsers).toHaveBeenCalledTimes(2));
  });

  it('a disabled account can be enabled', async () => {
    updateUser.mockResolvedValue({});
    render(<AdminUsers />);
    fireEvent.click(within(await rowOf('13900139000')).getByText('启用'));
    await waitFor(() =>
      expect(updateUser).toHaveBeenCalledWith(3, { disabled: false }),
    );
  });
});
