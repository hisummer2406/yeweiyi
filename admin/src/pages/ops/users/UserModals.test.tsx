import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { message } from 'antd';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import zhPages from '@/locales/zh-CN/pages';

const createUser = vi.fn();
const updateUser = vi.fn();
const moveUser = vi.fn();

vi.mock('@umijs/max', () => ({
  useIntl: () => ({
    formatMessage: ({ id }: { id: string }, values?: Record<string, string>) =>
      ((zhPages as Record<string, string>)[id] ?? id).replace(
        /\{(\w+)\}/g,
        (_, k) => values?.[k] ?? '',
      ),
  }),
}));
vi.mock('@/services/ant-design-pro/users', () => ({
  createUser,
  updateUser,
  moveUser,
}));

const { CreateUserModal, ResetPasswordModal, MoveUserModal } = await import(
  './UserModals'
);
const { ApiError } = await import('@/services/ant-design-pro/apiError');

const customers = [
  { id: 1, name: 'A 油站', createdAt: '' },
  { id: 2, name: 'B 油站', createdAt: '' },
];

const user = {
  id: 7,
  username: 'zhang',
  role: 'customer_admin' as const,
  customerId: 1,
  disabled: false,
  createdAt: '',
};

const pickOption = async (label: RegExp | string, option: string) => {
  fireEvent.mouseDown(screen.getByLabelText(label));
  fireEvent.click(await screen.findByText(option));
};

beforeEach(() => {
  vi.clearAllMocks();
  vi.spyOn(message, 'success').mockImplementation((() => {}) as never);
  vi.spyOn(message, 'error').mockImplementation((() => {}) as never);
});
afterEach(cleanup);

describe('CreateUserModal', () => {
  const setup = () => {
    const onDone = vi.fn();
    const onClose = vi.fn();
    render(
      <CreateUserModal
        open
        customers={customers}
        onClose={onClose}
        onDone={onDone}
      />,
    );
    return { onDone, onClose };
  };
  const fill = (username: string, password: string) => {
    fireEvent.change(screen.getByLabelText('用户名'), {
      target: { value: username },
    });
    fireEvent.change(screen.getByLabelText('初始密码'), {
      target: { value: password },
    });
  };
  const submit = () =>
    fireEvent.click(screen.getByRole('button', { name: /创\s*建/ }));

  it('platform_admin is created without a customerId', async () => {
    createUser.mockResolvedValue({ id: 9 });
    const { onDone } = setup();
    fill('  root  ', 'password1');
    await pickOption('角色', '平台管理员');
    expect(screen.queryByLabelText('所属客户')).toBeNull();
    submit();
    await waitFor(() => expect(createUser).toHaveBeenCalledTimes(1));
    expect(createUser).toHaveBeenCalledWith({
      username: 'root',
      password: 'password1',
      role: 'platform_admin',
    });
    await waitFor(() => expect(onDone).toHaveBeenCalled());
  });

  it('customer roles require a customer and send its id', async () => {
    createUser.mockResolvedValue({ id: 9 });
    setup();
    fill('zhang', 'password1');
    await pickOption('角色', '客户管理员');
    await screen.findByLabelText('所属客户');
    submit();
    expect(await screen.findByText('请选择客户')).toBeTruthy();
    expect(createUser).not.toHaveBeenCalled();
    await pickOption('所属客户', 'B 油站');
    submit();
    await waitFor(() =>
      expect(createUser).toHaveBeenCalledWith({
        username: 'zhang',
        password: 'password1',
        role: 'customer_admin',
        customerId: 2,
      }),
    );
  });

  it('switching back to platform_admin drops a customer picked earlier', async () => {
    createUser.mockResolvedValue({ id: 9 });
    setup();
    fill('root', 'password1');
    await pickOption('角色', '客户管理员');
    await screen.findByLabelText('所属客户');
    await pickOption('所属客户', 'B 油站');
    await pickOption('角色', '平台管理员');
    submit();
    await waitFor(() => expect(createUser).toHaveBeenCalledTimes(1));
    expect(createUser.mock.calls[0][0]).not.toHaveProperty('customerId');
  });

  it('rejects a password shorter than 8 bytes and one over 72 bytes', async () => {
    setup();
    await pickOption('角色', '平台管理员');
    fill('root', 'short');
    submit();
    expect(await screen.findByText(/8-72 字节/)).toBeTruthy();
    // 25 个汉字 = 75 字节，字符数不到 72 但字节数超限
    fill('root', '密'.repeat(25));
    submit();
    await waitFor(() => expect(screen.getByText(/8-72 字节/)).toBeTruthy());
    expect(createUser).not.toHaveBeenCalled();
  });

  it('409 shows the username-exists message and keeps the dialog open', async () => {
    createUser.mockRejectedValue(new ApiError(409, 'username already exists'));
    const { onDone, onClose } = setup();
    fill('root', 'password1');
    await pickOption('角色', '平台管理员');
    submit();
    await waitFor(() =>
      expect(message.error).toHaveBeenCalledWith('用户名已存在'),
    );
    expect(onDone).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('stays silent when the auth failure was already handled', async () => {
    createUser.mockRejectedValue(new ApiError(401, undefined, true));
    setup();
    fill('root', 'password1');
    await pickOption('角色', '平台管理员');
    submit();
    await waitFor(() => expect(createUser).toHaveBeenCalled());
    expect(message.error).not.toHaveBeenCalled();
  });

  it('a second form submit (Enter key) while the request is pending does not submit twice', async () => {
    let resolve: (v: unknown) => void = () => {};
    createUser.mockReturnValue(new Promise((r) => (resolve = r)));
    setup();
    fill('root', 'password1');
    await pickOption('角色', '平台管理员');
    const form = screen.getByLabelText('用户名').closest('form') as HTMLElement;
    fireEvent.submit(form);
    await waitFor(() => expect(createUser).toHaveBeenCalledTimes(1));
    fireEvent.submit(form);
    fireEvent.submit(form);
    await new Promise((r) => setTimeout(r, 50));
    expect(createUser).toHaveBeenCalledTimes(1);
    resolve({ id: 1 });
  });
});

describe('ResetPasswordModal', () => {
  it('sends only the password for the chosen user', async () => {
    updateUser.mockResolvedValue({ id: 7 });
    const onDone = vi.fn();
    render(
      <ResetPasswordModal
        open
        user={user}
        onClose={() => {}}
        onDone={onDone}
      />,
    );
    fireEvent.change(screen.getByLabelText('新密码'), {
      target: { value: 'newpassword1' },
    });
    fireEvent.click(screen.getByRole('button', { name: /重\s*置/ }));
    await waitFor(() =>
      expect(updateUser).toHaveBeenCalledWith(7, { password: 'newpassword1' }),
    );
    await waitFor(() => expect(onDone).toHaveBeenCalled());
  });
});

describe('MoveUserModal', () => {
  it('offers every customer except the current one and moves the user', async () => {
    moveUser.mockResolvedValue({ id: 7 });
    const onDone = vi.fn();
    render(
      <MoveUserModal
        open
        user={user}
        customers={customers}
        onClose={() => {}}
        onDone={onDone}
      />,
    );
    expect(screen.getByText(/设备绑定|已绑定的设备/)).toBeTruthy();
    fireEvent.mouseDown(screen.getByLabelText('换到客户'));
    expect(await screen.findByText('B 油站')).toBeTruthy();
    expect(screen.queryByText('A 油站')).toBeNull();
    fireEvent.click(screen.getByText('B 油站'));
    fireEvent.click(screen.getByRole('button', { name: /确认换客户/ }));
    await waitFor(() => expect(moveUser).toHaveBeenCalledWith(7, 2));
    await waitFor(() => expect(onDone).toHaveBeenCalled());
  });
});
