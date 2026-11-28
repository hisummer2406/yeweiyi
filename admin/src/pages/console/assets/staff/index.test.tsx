import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import zhPages from '@/locales/zh-CN/pages';

const listUsers = vi.fn();
const replace = vi.fn();

vi.mock('@umijs/max', () => ({
  history: { replace, push: vi.fn() },
  useIntl: () => ({
    formatMessage: ({ id }: { id: string }) =>
      (zhPages as Record<string, string>)[id] ?? id,
  }),
}));
vi.mock('@/services/ant-design-pro/users', () => ({ listUsers }));
vi.mock('@/services/ant-design-pro/customers', () => ({
  listCustomers: vi.fn().mockResolvedValue([]),
}));
vi.mock('@/services/ant-design-pro/devices', () => ({
  listDevices: vi.fn().mockResolvedValue([]),
}));
vi.mock('@/services/ant-design-pro/stations', () => ({
  listStations: vi.fn().mockResolvedValue([]),
}));
vi.mock('./TankGrantsModal', () => ({
  default: ({ user }: { user?: { phone: string } }) =>
    user ? <div>grant-modal:{user.phone}</div> : null,
}));

const { default: StaffGrants } = await import('./index');

const staff = (id: number, phone: string) => ({
  id,
  username: null,
  phone,
  role: 'customer_user',
  customerId: 5,
  disabled: false,
  createdAt: '',
});

beforeEach(() => {
  vi.clearAllMocks();
  listUsers.mockResolvedValue([
    staff(3, '13900139000'),
    staff(4, '13700137000'),
  ]);
});
afterEach(() => {
  cleanup();
  window.history.replaceState(null, '', '/');
});

describe('StaffGrants', () => {
  it('opens the grant dialog for ?user=<id> once the list has loaded, and clears the param', async () => {
    window.history.replaceState(null, '', '/assets/staff?user=4');
    render(<StaffGrants />);
    expect(await screen.findByText('grant-modal:13700137000')).toBeTruthy();
    expect(replace).toHaveBeenCalledWith('/assets/staff');
  });

  it('opens nothing without the param', async () => {
    render(<StaffGrants />);
    await screen.findByText('13900139000');
    await waitFor(() => expect(listUsers).toHaveBeenCalled());
    expect(screen.queryByText(/grant-modal/)).toBeNull();
  });

  it('an unknown ?user= id just shows the list', async () => {
    window.history.replaceState(null, '', '/assets/staff?user=999');
    render(<StaffGrants />);
    await screen.findByText('13900139000');
    expect(screen.queryByText(/grant-modal/)).toBeNull();
  });

  it('searches by phone fragment (keeping leading zeros) and clears back to the full list', async () => {
    listUsers.mockResolvedValue([
      staff(3, '13900139000'),
      staff(4, '01370137000'),
    ]);
    render(<StaffGrants />);
    await screen.findByText('13900139000');
    const box = screen.getByPlaceholderText('搜索手机号/所属客户');
    const submit = (v: string) => {
      fireEvent.change(box, { target: { value: v } });
      fireEvent.keyDown(box, { key: 'Enter', code: 'Enter', keyCode: 13 });
      fireEvent.keyUp(box, { key: 'Enter', code: 'Enter', keyCode: 13 });
    };
    submit(' 0137 ');
    await screen.findByText('01370137000');
    expect(screen.queryByText('13900139000')).toBeNull();
    submit('999999');
    expect(await screen.findByText('无搜索结果')).toBeTruthy();
    submit('');
    expect(await screen.findByText('13900139000')).toBeTruthy();
  });
});
