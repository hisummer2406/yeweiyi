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

const {
  CreateUserModal,
  EditAccountModal,
  MoveUserModal,
  buildCreateUserParams,
} = await import('./UserModals');
const { ApiError } = await import('@/services/ant-design-pro/apiError');

const customers = [
  { id: 1, name: 'A 油站', createdAt: '' },
  { id: 2, name: 'B 油站', createdAt: '' },
];

const user = {
  id: 7,
  username: null,
  phone: '13800138000',
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
  vi.spyOn(message, 'warning').mockImplementation((() => {}) as never);
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
        allowedRoles={['platform_admin', 'customer_admin', 'customer_user']}
        onClose={onClose}
        onDone={onDone}
      />,
    );
    return { onDone, onClose };
  };
  // 字段按角色出现，选角色后要等它们渲染出来再填。
  const type = async (label: string, value: string) =>
    fireEvent.change(await screen.findByLabelText(label), {
      target: { value },
    });
  const submit = () =>
    fireEvent.click(screen.getByRole('button', { name: /创\s*建/ }));

  it('platform_admin is created with a username and password, nothing else', async () => {
    createUser.mockResolvedValue({ id: 9 });
    const { onDone } = setup();
    await pickOption('角色', '平台管理员');
    await type('用户名', '  root  ');
    await type('初始密码', 'password1');
    expect(screen.queryByLabelText('所属客户')).toBeNull();
    expect(screen.queryByLabelText('账号（手机号/用户名）')).toBeNull();
    submit();
    await waitFor(() => expect(createUser).toHaveBeenCalledTimes(1));
    // 页面据新账号决定是否跳转（员工 → 员工授权）
    await waitFor(() => expect(onDone).toHaveBeenCalledWith({ id: 9 }));
    expect(createUser).toHaveBeenCalledWith({
      role: 'platform_admin',
      username: 'root',
      password: 'password1',
    });
    await waitFor(() => expect(onDone).toHaveBeenCalled());
  });

  it('customer_admin needs a phone, a password and a customer; the phone is sent normalized', async () => {
    createUser.mockResolvedValue({ id: 9 });
    setup();
    await pickOption('角色', '客户管理员');
    await type('账号（手机号/用户名）', '+86 138 0013 8000');
    expect(screen.queryByLabelText('用户名')).toBeNull();
    await type('初始密码', 'password1');
    submit();
    expect(await screen.findByText('请选择客户')).toBeTruthy();
    expect(createUser).not.toHaveBeenCalled();
    await pickOption('所属客户', 'B 油站');
    submit();
    await waitFor(() =>
      expect(createUser).toHaveBeenCalledWith({
        role: 'customer_admin',
        phone: '13800138000',
        password: 'password1',
        customerId: 2,
      }),
    );
  });

  it('a phone staff account may be created without a password, and none is sent', async () => {
    createUser.mockResolvedValue({ id: 9 });
    setup();
    await pickOption('角色', '客户用户');
    await type('账号（手机号/用户名）', '13900139000');
    expect(await screen.findByLabelText('初始密码（可选）')).toBeTruthy();
    expect(screen.getByText(/不能登录网页后台/)).toBeTruthy();
    await pickOption('所属客户', 'A 油站');
    submit();
    await waitFor(() => expect(createUser).toHaveBeenCalledTimes(1));
    expect(createUser.mock.calls[0][0]).toEqual({
      role: 'customer_user',
      phone: '13900139000',
      customerId: 1,
    });
  });

  it('a phone staff account can be given an initial password', async () => {
    createUser.mockResolvedValue({ id: 9 });
    setup();
    await pickOption('角色', '客户用户');
    await type('账号（手机号/用户名）', '13900139000');
    await type('初始密码（可选）', 'password1');
    await pickOption('所属客户', 'A 油站');
    submit();
    await waitFor(() => expect(createUser).toHaveBeenCalledTimes(1));
    expect(createUser.mock.calls[0][0]).toEqual({
      role: 'customer_user',
      phone: '13900139000',
      password: 'password1',
      customerId: 1,
    });
  });

  it('a username staff account needs a username and a password, and sends no phone', async () => {
    createUser.mockResolvedValue({ id: 9 });
    setup();
    await pickOption('角色', '客户用户');
    await type('账号（手机号/用户名）', 'Staff.One');
    await type('初始密码', 'password1');
    await pickOption('所属客户', 'A 油站');
    submit();
    await waitFor(() => expect(createUser).toHaveBeenCalledTimes(1));
    const body = createUser.mock.calls[0][0];
    expect(body).toEqual({
      role: 'customer_user',
      username: 'Staff.One',
      password: 'password1',
      customerId: 1,
    });
    expect(body).not.toHaveProperty('phone');
  });

  it('rejects a username with illegal characters before sending', async () => {
    setup();
    await pickOption('角色', '客户用户');
    await type('账号（手机号/用户名）', 'bad name!');
    await type('初始密码', 'password1');
    await pickOption('所属客户', 'A 油站');
    submit();
    expect(
      await screen.findByText(/请输入 11 位大陆手机号，或 3-32 位/),
    ).toBeTruthy();
    expect(createUser).not.toHaveBeenCalled();
  });

  it('a taken username says so without naming any other account', async () => {
    createUser.mockRejectedValue(
      new ApiError(409, 'username taken', false, 'username_taken'),
    );
    setup();
    await pickOption('角色', '客户用户');
    await type('账号（手机号/用户名）', 'staff.one');
    await type('初始密码', 'password1');
    await pickOption('所属客户', 'A 油站');
    submit();
    await waitFor(() =>
      expect(message.error).toHaveBeenCalledWith('该用户名已被使用。'),
    );
  });

  it('rejects a malformed account before sending anything', async () => {
    setup();
    await pickOption('角色', '客户用户');
    await type('账号（手机号/用户名）', 'ab');
    await pickOption('所属客户', 'A 油站');
    submit();
    expect(
      await screen.findByText(/请输入 11 位大陆手机号，或 3-32 位/),
    ).toBeTruthy();
    expect(createUser).not.toHaveBeenCalled();
  });

  it('switching to platform_admin drops a customer and a phone picked earlier', async () => {
    createUser.mockResolvedValue({ id: 9 });
    setup();
    await pickOption('角色', '客户管理员');
    await type('账号（手机号/用户名）', '13800138000');
    await pickOption('所属客户', 'B 油站');
    await pickOption('角色', '平台管理员');
    await type('用户名', 'root');
    await type('初始密码', 'password1');
    await waitFor(() =>
      expect(screen.queryByLabelText('账号（手机号/用户名）')).toBeNull(),
    );
    submit();
    await waitFor(() => expect(createUser).toHaveBeenCalledTimes(1));
    expect(createUser.mock.calls[0][0]).toEqual({
      role: 'platform_admin',
      username: 'root',
      password: 'password1',
    });
  });

  it('rejects a password shorter than 8 bytes and one over 72 bytes', async () => {
    setup();
    await pickOption('角色', '平台管理员');
    await type('用户名', 'root');
    await type('初始密码', 'short');
    submit();
    expect(await screen.findByText(/8-72 字节/)).toBeTruthy();
    // 25 个汉字 = 75 字节，字符数不到 72 但字节数超限
    await type('初始密码', '密'.repeat(25));
    submit();
    await waitFor(() => expect(screen.getByText(/8-72 字节/)).toBeTruthy());
    expect(createUser).not.toHaveBeenCalled();
  });

  it('a duplicate phone says so without naming a customer, and keeps the dialog open', async () => {
    const err = new ApiError(
      409,
      'phone number already registered',
      false,
      'phone_exists',
    );
    createUser.mockRejectedValue(err);
    const { onDone, onClose } = setup();
    await pickOption('角色', '客户用户');
    await type('账号（手机号/用户名）', '13900139000');
    await pickOption('所属客户', 'A 油站');
    submit();
    await waitFor(() =>
      expect(message.error).toHaveBeenCalledWith('该手机号已被注册。'),
    );
    expect(onDone).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('a duplicate platform username keeps the old username message', async () => {
    createUser.mockRejectedValue(new ApiError(409, 'username already exists'));
    setup();
    await pickOption('角色', '平台管理员');
    await type('用户名', 'root');
    await type('初始密码', 'password1');
    submit();
    await waitFor(() =>
      expect(message.error).toHaveBeenCalledWith('用户名已存在'),
    );
  });

  it('stays silent when the auth failure was already handled', async () => {
    createUser.mockRejectedValue(new ApiError(401, undefined, true));
    setup();
    await pickOption('角色', '平台管理员');
    await type('用户名', 'root');
    await type('初始密码', 'password1');
    submit();
    await waitFor(() => expect(createUser).toHaveBeenCalled());
    expect(message.error).not.toHaveBeenCalled();
  });

  it('a second form submit (Enter key) while the request is pending does not submit twice', async () => {
    let resolve: (v: unknown) => void = () => {};
    createUser.mockReturnValue(new Promise((r) => (resolve = r)));
    setup();
    await pickOption('角色', '平台管理员');
    await type('用户名', 'root');
    await type('初始密码', 'password1');
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

describe('新建账号的角色', () => {
  const renderModal = (allowedRoles?: API.UserRole[]) =>
    render(
      <CreateUserModal
        open
        customers={customers}
        allowedRoles={allowedRoles}
        onClose={vi.fn()}
        onDone={vi.fn()}
      />,
    );

  it('does not offer 客户管理员 by default (it is created with the customer)', async () => {
    renderModal();
    fireEvent.mouseDown(await screen.findByLabelText('角色'));
    expect(await screen.findByText('平台管理员')).toBeTruthy();
    expect(screen.getByText('客户用户')).toBeTruthy();
    expect(screen.queryByText('客户管理员')).toBeNull();
  });

  it('a single allowed role is used directly, with no role dropdown', async () => {
    renderModal(['customer_user']);
    expect(screen.queryByLabelText('角色')).toBeNull();
    // 员工：一个“手机号或用户名”输入框；密码在输入手机号时可选
    expect(await screen.findByLabelText('账号（手机号/用户名）')).toBeTruthy();
    expect(screen.getByPlaceholderText('手机号或用户名')).toBeTruthy();
  });
});

describe('一客户一管理员（换客户）', () => {
  it('moving a customer admin into a customer that already has one is disabled', async () => {
    render(
      <MoveUserModal
        open
        user={
          {
            id: 5,
            username: null,
            phone: '13800138000',
            role: 'customer_admin',
            customerId: 1,
            disabled: false,
            createdAt: '',
          } as API.AdminUser
        }
        customers={customers}
        adminCustomerIds={[2]}
        onClose={vi.fn()}
        onDone={vi.fn()}
      />,
    );
    fireEvent.mouseDown(await screen.findByLabelText('换到客户'));
    const taken = await screen.findByText('B 油站（已有管理员）');
    expect(
      taken.closest('.ant-select-item')?.getAttribute('aria-disabled'),
    ).toBe('true');
  });
});

describe('buildCreateUserParams', () => {
  it('a phone staff request carries no username and no empty password', () => {
    expect(
      buildCreateUserParams({
        role: 'customer_user',
        account: '139 0013 9000',
        customerId: 3,
        password: '',
        username: 'leftover',
      }),
    ).toEqual({ role: 'customer_user', phone: '13900139000', customerId: 3 });
  });

  it('a username request carries no phone, whatever was typed before', () => {
    expect(
      buildCreateUserParams({
        role: 'customer_user',
        account: ' staff.one ',
        username: 'leftover',
        password: 'password1',
        customerId: 3,
      }),
    ).toEqual({
      role: 'customer_user',
      username: 'staff.one',
      password: 'password1',
      customerId: 3,
    });
  });

  it('a phone customer admin always carries its password', () => {
    expect(
      buildCreateUserParams({
        role: 'customer_admin',
        account: '13800138000',
        password: 'password1',
        customerId: 3,
      }),
    ).toEqual({
      role: 'customer_admin',
      phone: '13800138000',
      password: 'password1',
      customerId: 3,
    });
  });
});

describe('EditAccountModal', () => {
  const save = () =>
    fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }));
  const open = (u: API.AdminUser, onDone = vi.fn()) =>
    render(
      <EditAccountModal open user={u} onClose={() => {}} onDone={onDone} />,
    );

  it('sends only the password when the phone is untouched', async () => {
    updateUser.mockResolvedValue({ id: 7 });
    const onDone = vi.fn();
    open(user, onDone);
    fireEvent.change(screen.getByLabelText('新密码'), {
      target: { value: 'newpassword1' },
    });
    save();
    await waitFor(() =>
      expect(updateUser).toHaveBeenCalledWith(7, { password: 'newpassword1' }),
    );
    await waitFor(() => expect(onDone).toHaveBeenCalled());
  });

  it('sends the normalized phone and the password together in one request', async () => {
    updateUser.mockResolvedValue({ id: 7 });
    open(user);
    fireEvent.change(screen.getByLabelText('手机号'), {
      target: { value: '+86 138-0000-0002' },
    });
    fireEvent.change(screen.getByLabelText('新密码'), {
      target: { value: 'newpassword1' },
    });
    save();
    await waitFor(() =>
      expect(updateUser).toHaveBeenCalledWith(7, {
        phone: '13800000002',
        password: 'newpassword1',
      }),
    );
  });

  it('sends only the phone when the password is blank', async () => {
    updateUser.mockResolvedValue({ id: 7 });
    open(user);
    fireEvent.change(screen.getByLabelText('手机号'), {
      target: { value: '13800000002' },
    });
    save();
    await waitFor(() =>
      expect(updateUser).toHaveBeenCalledWith(7, { phone: '13800000002' }),
    );
  });

  it('rejects an invalid phone and does not call the API when nothing changed', async () => {
    open(user);
    fireEvent.change(screen.getByLabelText('手机号'), {
      target: { value: '12345' },
    });
    save();
    expect(await screen.findByText(/有效的 11 位大陆手机号/)).toBeTruthy();
    fireEvent.change(screen.getByLabelText('手机号'), {
      target: { value: user.phone },
    });
    save();
    await waitFor(() => expect(message.warning).toHaveBeenCalled());
    expect(updateUser).not.toHaveBeenCalled();
  });

  it('shows no phone field for username accounts and platform admins', () => {
    open({ ...user, phone: null, username: 'acme' });
    expect(screen.queryByLabelText('手机号')).toBeNull();
    expect(screen.getByLabelText('新密码')).toBeTruthy();
  });

  it('shows the duplicate-phone message and keeps the dialog open', async () => {
    updateUser.mockRejectedValue(
      new ApiError(409, 'phone exists', false, 'phone_exists'),
    );
    const onDone = vi.fn();
    open(user, onDone);
    fireEvent.change(screen.getByLabelText('手机号'), {
      target: { value: '13800000002' },
    });
    save();
    await waitFor(() =>
      expect(message.error).toHaveBeenCalledWith('该手机号已被注册。'),
    );
    expect(onDone).not.toHaveBeenCalled();
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
