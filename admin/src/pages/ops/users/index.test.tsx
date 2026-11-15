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
  history: { replace: vi.fn() },
}));
vi.mock('@/services/ant-design-pro/users', () => ({
  listUsers,
  updateUser,
  createUser: vi.fn(),
  moveUser: vi.fn(),
}));
vi.mock('@/services/ant-design-pro/customers', () => ({ listCustomers }));

const { default: UsersPanel } = await import('./UsersPanel');
const { default: AdminUsers } = await import('./index');
const { default: PlatformAdmins } = await import('../platform-admins');
// 面板的通用行为（搜索、层级、操作）直接测面板；平台管理员账号页只管平台管理员，见末尾
const Panel = () => <UsersPanel />;

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
    render(<Panel />);
    const row = await rowOf('13800138000');
    expect(within(row).getByText('客户管理员')).toBeTruthy();
    expect(within(row).getByText('A 油站')).toBeTruthy();
  });

  it('falls back to #id when the customer list cannot load', async () => {
    listCustomers.mockRejectedValue(new Error('boom'));
    render(<Panel />);
    expect(within(await rowOf('13800138000')).getByText('#5')).toBeTruthy();
  });

  it('every account, staff included, can be edited (the only way to change a password)', async () => {
    render(<Panel />);
    const staff = await rowOf('13900139000');
    expect(within(staff).getByText('编辑账号')).toBeTruthy();
    const admin = await rowOf('13800138000');
    expect(within(admin).getByText('编辑账号')).toBeTruthy();
  });

  it('customer-side accounts are shown by phone, never by a username', async () => {
    render(<Panel />);
    const row = await rowOf('13800138000');
    expect(within(row).getByText('13800138000')).toBeTruthy();
    expect(screen.queryByText('null')).toBeNull();
  });

  it('disabling asks for confirmation, then calls updateUser', async () => {
    updateUser.mockResolvedValue({});
    render(<Panel />);
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
    render(<Panel />);
    fireEvent.click(within(await rowOf('13900139000')).getByText('启用'));
    await waitFor(() =>
      expect(updateUser).toHaveBeenCalledWith(3, { disabled: false }),
    );
  });

  it('searching by a phone fragment narrows the list without refetching', async () => {
    render(<Panel />);
    await rowOf('13800138000');
    const box = screen.getByPlaceholderText('搜索手机号/用户名');
    fireEvent.change(box, { target: { value: '9000' } });
    fireEvent.keyDown(box, { key: 'Enter', keyCode: 13 });
    await waitFor(() => expect(screen.queryByText('13800138000')).toBeNull());
    expect(screen.getByText('13900139000')).toBeTruthy();
    expect(listUsers).toHaveBeenCalledTimes(1);
  });

  it('a ?customer= URL parameter preselects the customer filter', async () => {
    window.history.replaceState(null, '', '/ops/users?customer=5');
    render(<Panel />);
    await rowOf('13800138000');
    expect(screen.queryByText('root')).toBeNull();
    window.history.replaceState(null, '', '/');
  });

  it('staff are listed flat next to their admin, not hidden inside the admin row', async () => {
    render(<Panel />);
    expect(await screen.findByText('13800138000')).toBeTruthy();
    expect(screen.getByText('13900139000')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /展开行/ })).toBeNull();
  });

  it('within a customer the admin comes before its staff', async () => {
    render(<Panel />);
    await screen.findByText('13900139000');
    const order = screen
      .getAllByText(/^1[3-9]\d{9}$/)
      .map((n) => n.textContent);
    expect(order.indexOf('13800138000')).toBeLessThan(
      order.indexOf('13900139000'),
    );
  });
});

describe('账号管理页（客户侧）', () => {
  it('lists customer-side accounts only, never platform admins', async () => {
    render(<AdminUsers />);
    expect(await screen.findByText('13800138000')).toBeTruthy();
    expect(screen.queryByText('root')).toBeNull();
  });

  it('new accounts are staff: one phone-or-username input and a customer, no role dropdown', async () => {
    render(<AdminUsers />);
    await screen.findByText('13800138000');
    fireEvent.click(screen.getByRole('button', { name: /新建账号/ }));
    expect(await screen.findByLabelText('账号（手机号/用户名）')).toBeTruthy();
    expect(screen.getByPlaceholderText('手机号或用户名')).toBeTruthy();
    expect(screen.getByLabelText('所属客户')).toBeTruthy();
    expect(screen.queryByLabelText('角色')).toBeNull();
  });
});

describe('平台管理员账号页', () => {
  it('only lists platform admins, with no customer or role columns/filters', async () => {
    render(<PlatformAdmins />);
    expect(await screen.findByText('root')).toBeTruthy();
    expect(screen.queryByText('13800138000')).toBeNull();
    expect(screen.queryByText('所属客户')).toBeNull();
    expect(screen.queryByText('按客户筛选')).toBeNull();
  });

  it('the signed-in user cannot disable themselves or be moved', async () => {
    render(<PlatformAdmins />);
    const self = await rowOf('root');
    expect(within(self).queryByText('停用')).toBeNull();
    expect(within(self).queryByText('启用')).toBeNull();
    expect(within(self).queryByText('换客户')).toBeNull();
    expect(within(self).getByText('编辑账号')).toBeTruthy();
  });

  it('creating an account offers only platform admin fields', async () => {
    render(<PlatformAdmins />);
    await screen.findByText('root');
    fireEvent.click(screen.getByRole('button', { name: /新建账号/ }));
    expect(await screen.findByLabelText('用户名')).toBeTruthy();
    expect(screen.queryByLabelText('角色')).toBeNull();
    expect(screen.queryByLabelText('手机号')).toBeNull();
  });
});
