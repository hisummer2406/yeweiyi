import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import zhPages from '@/locales/zh-CN/pages';

const listUsers = vi.fn();
const listCustomers = vi.fn();
let routeId = '5';

vi.mock('@umijs/max', () => ({
  useIntl: () => ({
    formatMessage: ({ id }: { id: string }, values?: Record<string, string>) =>
      ((zhPages as Record<string, string>)[id] ?? id).replace(
        /\{(\w+)\}/g,
        (_, k) => values?.[k] ?? '',
      ),
  }),
  useModel: () => ({ initialState: { currentUser: { userId: 1 } } }),
  useParams: () => ({ id: routeId }),
  history: { replace: vi.fn(), push: vi.fn() },
}));
vi.mock('@/services/ant-design-pro/users', () => ({
  listUsers,
  updateUser: vi.fn(),
  createUser: vi.fn(),
  moveUser: vi.fn(),
}));
vi.mock('@/services/ant-design-pro/customers', () => ({ listCustomers }));

const { default: CustomerAccounts } = await import('./index');

const user = (id: number, phone: string, role: string, customerId: number) => ({
  id,
  username: null,
  phone,
  role,
  customerId,
  disabled: false,
  createdAt: '2026-10-07T00:00:00Z',
});

beforeEach(() => {
  vi.clearAllMocks();
  routeId = '5';
  listUsers.mockResolvedValue([
    user(2, '13800138000', 'customer_admin', 5),
    user(3, '13900139000', 'customer_user', 5),
    user(4, '13700137000', 'customer_admin', 6),
  ]);
  listCustomers.mockResolvedValue([
    { id: 5, name: 'A 油站', createdAt: '' },
    { id: 6, name: 'B 油站', createdAt: '' },
  ]);
});
afterEach(cleanup);

describe('CustomerAccounts', () => {
  it("only lists that customer's accounts and titles the page with its name", async () => {
    render(<CustomerAccounts />);
    expect(await screen.findByText('13800138000')).toBeTruthy();
    expect(screen.queryByText('13700137000')).toBeNull();
    await waitFor(() =>
      expect(screen.getAllByText('A 油站 的账号').length).toBeGreaterThan(0),
    );
  });

  it('has no customer filter: the customer is fixed by the route', async () => {
    render(<CustomerAccounts />);
    await screen.findByText('13800138000');
    expect(screen.queryByText('按客户筛选')).toBeNull();
  });

  it('rejects a non-numeric id', () => {
    routeId = 'abc';
    render(<CustomerAccounts />);
    expect(screen.getByText('客户不存在')).toBeTruthy();
    expect(listUsers).not.toHaveBeenCalled();
  });
});
