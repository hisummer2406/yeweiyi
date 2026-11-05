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
const createUser = vi.fn();
const completeCustomerOnboarding = vi.fn();
const createStation = vi.fn();
const deleteStation = vi.fn();
const assignDeviceStation = vi.fn();
const registerDevice = vi.fn();

const push = vi.fn();
vi.mock('@umijs/max', () => ({
  history: { push },
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
  completeCustomerOnboarding,
}));
vi.mock('@/services/ant-design-pro/stations', () => ({
  createStation,
  deleteStation,
  assignDeviceStation,
}));
vi.mock('@/services/ant-design-pro/devices', () => ({ registerDevice }));

vi.mock('@/services/ant-design-pro/users', () => ({ createUser }));

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

/** 填写新建客户弹窗：名称 + 客户管理员手机号 + 初始密码 */
const fill = (
  nameInput: HTMLElement,
  name: string,
  phone = '13800138000',
  password = 'password123',
) => {
  fireEvent.change(nameInput, { target: { value: name } });
  fireEvent.change(screen.getByLabelText('客户管理员手机号'), {
    target: { value: phone },
  });
  fireEvent.change(screen.getByLabelText('初始密码'), {
    target: { value: password },
  });
  fireEvent.change(screen.getByLabelText('油站名称'), {
    target: { value: '一号站' },
  });
  fireEvent.change(screen.getByLabelText('设备出厂编号'), {
    target: { value: 'ZCT000001' },
  });
};
const submitCreate = () =>
  fireEvent.click(screen.getByRole('button', { name: /创\s*建/ }));

describe('AdminCustomers', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    createUser.mockResolvedValue({ id: 10 });
    createStation.mockResolvedValue({ id: 5, customerId: 2, name: '一号站' });
    registerDevice.mockResolvedValue({
      kind: 'created',
      deviceName: 'ZCT000001',
    });
    assignDeviceStation.mockResolvedValue(undefined);
    completeCustomerOnboarding.mockResolvedValue({ status: 'active' });
    deleteStation.mockResolvedValue(undefined);
    deleteCustomer.mockResolvedValue(undefined);
    listCustomers.mockResolvedValue(rows);
    vi.spyOn(message, 'success').mockImplementation((() => {}) as never);
    vi.spyOn(message, 'error').mockImplementation((() => {}) as never);
  });
  afterEach(cleanup);

  it('lists customers', async () => {
    render(<AdminCustomers />);
    expect(await screen.findByText('default')).toBeTruthy();
  });

  it('creates customer, admin, station and device, then completes onboarding in one go', async () => {
    createCustomer.mockResolvedValue({
      id: 2,
      name: 'A 油站',
      createdAt: '',
      onboardingStatus: 'configuring',
    });
    const input = await openModal();
    fill(input, '  A 油站  ');
    submitCreate();
    await waitFor(() =>
      expect(createCustomer).toHaveBeenCalledWith({ name: 'A 油站' }),
    );
    await waitFor(() =>
      expect(createUser).toHaveBeenCalledWith({
        role: 'customer_admin',
        phone: '13800138000',
        password: 'password123',
        customerId: 2,
      }),
    );
    await waitFor(() =>
      expect(assignDeviceStation).toHaveBeenCalledWith('ZCT000001', 5),
    );
    expect(registerDevice).toHaveBeenCalledWith({
      deviceName: 'ZCT000001',
      customerId: 2,
    });
    await waitFor(() =>
      expect(completeCustomerOnboarding).toHaveBeenCalledWith(2),
    );
    await waitFor(() => expect(message.success).toHaveBeenCalled());
    // 一次建完就已开通，不再进向导
    expect(push).not.toHaveBeenCalled();
  });

  it('a device failure removes the empty station and carries on in the wizard', async () => {
    const warn = vi
      .spyOn(message, 'warning')
      .mockImplementation((() => {}) as never);
    createCustomer.mockResolvedValue({
      id: 2,
      name: 'A 油站',
      createdAt: '',
      onboardingStatus: 'configuring',
    });
    registerDevice.mockRejectedValue(
      new ApiError(409, 'device belongs to another customer'),
    );
    const input = await openModal();
    fill(input, 'A 油站');
    submitCreate();
    await waitFor(() => expect(deleteStation).toHaveBeenCalledWith(5));
    await waitFor(() => expect(warn).toHaveBeenCalled());
    expect(completeCustomerOnboarding).not.toHaveBeenCalled();
    await waitFor(() =>
      expect(push).toHaveBeenCalledWith('/assets/customers/2/onboarding'),
    );
  });

  it('legacy cloud (no onboardingStatus) does not open the wizard, just reloads the list', async () => {
    createCustomer.mockResolvedValue({ id: 2, name: 'A 油站', createdAt: '' });
    const input = await openModal();
    const before = listCustomers.mock.calls.length;
    fill(input, 'A 油站');
    submitCreate();
    await waitFor(() => expect(createUser).toHaveBeenCalled());
    await waitFor(() =>
      expect(listCustomers.mock.calls.length).toBeGreaterThan(before),
    );
    expect(push).not.toHaveBeenCalled();
  });

  it('shows onboarding status and the wizard entry only for configuring customers', async () => {
    listCustomers.mockResolvedValue([
      { id: 1, name: 'default', createdAt: '', onboardingStatus: 'active' },
      { id: 2, name: 'A 油站', createdAt: '', onboardingStatus: 'configuring' },
      { id: 3, name: 'B 油站', createdAt: '', onboardingStatus: 'active' },
      { id: 4, name: 'C 油站', createdAt: '' },
    ]);
    render(<AdminCustomers />);
    const a = await rowOf('A 油站');
    expect(within(a).getByText('配置中')).toBeTruthy();
    fireEvent.click(within(a).getByText('开通向导'));
    expect(push).toHaveBeenCalledWith('/assets/customers/2/onboarding');
    const b = await rowOf('B 油站');
    expect(within(b).getByText('已开通')).toBeTruthy();
    expect(within(b).queryByText('开通向导')).toBeNull();
    // 旧版云端没有该字段：显示「未知」，不当成已开通
    expect(within(await rowOf('C 油站')).getByText('未知')).toBeTruthy();
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
    fill(input, 'default');
    submitCreate();
    await waitFor(() =>
      expect(message.error).toHaveBeenCalledWith('客户名称已存在'),
    );
    expect(screen.getByPlaceholderText('新客户名称')).toBeTruthy();
    expect(createUser).not.toHaveBeenCalled();
  });

  it('stays silent when the auth failure was already handled', async () => {
    createCustomer.mockRejectedValue(new ApiError(401, undefined, true));
    const input = await openModal();
    fill(input, 'X');
    submitCreate();
    await waitFor(() => expect(createCustomer).toHaveBeenCalled());
    expect(message.error).not.toHaveBeenCalled();
  });

  it('rolls the new customer back when the admin cannot be created, keeping the form', async () => {
    createCustomer.mockResolvedValue({
      id: 7,
      name: 'Z',
      createdAt: '',
      onboardingStatus: 'configuring',
    });
    createUser.mockRejectedValue(
      new ApiError(409, 'dup', false, 'phone_exists'),
    );
    const input = await openModal();
    fill(input, 'Z');
    submitCreate();
    await waitFor(() => expect(deleteCustomer).toHaveBeenCalledWith(7));
    await waitFor(() => expect(message.error).toHaveBeenCalled());
    expect(push).not.toHaveBeenCalled();
    // 弹窗保留、已填内容不丢，改完手机号可直接重试
    expect(
      (screen.getByLabelText('客户管理员手机号') as HTMLInputElement).value,
    ).toBe('13800138000');
  });

  it('warns when rollback fails too, leaving a customer without an admin', async () => {
    const warn = vi
      .spyOn(message, 'warning')
      .mockImplementation((() => {}) as never);
    createCustomer.mockResolvedValue({ id: 7, name: 'Z', createdAt: '' });
    createUser.mockRejectedValue(new ApiError(500, 'boom'));
    deleteCustomer.mockRejectedValue(new ApiError(500, 'boom'));
    const input = await openModal();
    fill(input, 'Z');
    submitCreate();
    await waitFor(() => expect(warn).toHaveBeenCalled());
    expect(push).not.toHaveBeenCalled();
  });

  it('rejects an invalid admin phone without calling the API', async () => {
    const input = await openModal();
    fill(input, 'Z', '123');
    submitCreate();
    expect(
      await screen.findByText('请输入有效的 11 位大陆手机号'),
    ).toBeTruthy();
    expect(createCustomer).not.toHaveBeenCalled();
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
    fill(input, 'Z');
    const form = input.closest('form') as HTMLElement;
    fireEvent.submit(form);
    await waitFor(() => expect(createCustomer).toHaveBeenCalledTimes(1));
    fireEvent.submit(form);
    fireEvent.submit(form);
    await new Promise((r) => setTimeout(r, 50));
    expect(createCustomer).toHaveBeenCalledTimes(1);
    resolve({ id: 9, name: 'Z', createdAt: '' });
    await waitFor(() => expect(createUser).toHaveBeenCalledTimes(1));
  });
});
