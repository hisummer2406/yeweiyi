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

const registerDevice = vi.fn();
const listCustomers = vi.fn();
const createCustomer = vi.fn();

vi.mock('@umijs/max', () => ({
  useIntl: () => ({
    formatMessage: ({ id }: { id: string }, values?: Record<string, string>) =>
      ((zhPages as Record<string, string>)[id] ?? id).replace(
        /\{(\w+)\}/g,
        (_, k) => values?.[k] ?? '',
      ),
  }),
}));
vi.mock('@/services/ant-design-pro/devices', () => ({ registerDevice }));
vi.mock('@/services/ant-design-pro/customers', () => ({
  listCustomers,
  createCustomer,
}));

const { default: RegisterDeviceModal } = await import('./RegisterDeviceModal');
const { ApiError } = await import('@/services/ant-design-pro/apiError');

const customers = [
  { id: 3, name: 'A 油站', createdAt: '2026-10-07T00:00:00Z' },
  { id: 4, name: 'B 油站', createdAt: '2026-10-07T00:00:00Z' },
];

const setup = (withAssign = false) => {
  const onClose = vi.fn();
  const onRegistered = vi.fn();
  const onAssignStation = vi.fn();
  render(
    <RegisterDeviceModal
      open
      onClose={onClose}
      onRegistered={onRegistered}
      onAssignStation={withAssign ? onAssignStation : undefined}
    />,
  );
  return { onClose, onRegistered, onAssignStation };
};

const codeInput = () =>
  screen.getByPlaceholderText(
    zhPages['pages.ops.devices.form.code.placeholder'],
  );

const typeCode = (value: string) =>
  fireEvent.change(codeInput(), { target: { value } });

const pickCustomer = async (name: string) => {
  const select = await screen.findByRole('combobox');
  fireEvent.mouseDown(select);
  fireEvent.click(await screen.findByTitle(name));
};

const submit = () =>
  fireEvent.click(screen.getByRole('button', { name: /登\s*记$/ }));

describe('RegisterDeviceModal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listCustomers.mockResolvedValue(customers);
    vi.spyOn(message, 'success').mockImplementation((() => undefined) as never);
    vi.spyOn(message, 'error').mockImplementation((() => undefined) as never);
  });
  afterEach(() => cleanup());

  it('does not send a request without a customer', async () => {
    setup();
    typeCode('ZCT000001');
    submit();
    expect(
      await screen.findByText(
        zhPages['pages.ops.devices.form.customer.required'],
      ),
    ).toBeInTheDocument();
    expect(registerDevice).not.toHaveBeenCalled();
  });

  it('rejects an invalid code without sending', async () => {
    setup();
    typeCode('ZCT/01');
    await pickCustomer('A 油站');
    submit();
    expect(
      await screen.findByText(zhPages['pages.ops.devices.form.code.invalid']),
    ).toBeInTheDocument();
    expect(registerDevice).not.toHaveBeenCalled();
  });

  it('shows the candidate for a short code and blocks submit until confirmed', async () => {
    setup();
    typeCode('000001');
    await pickCustomer('A 油站');
    expect(screen.getAllByText(/ZCT000001/).length).toBeGreaterThan(0);
    submit();
    expect(
      await screen.findByText(
        zhPages['pages.ops.devices.form.code.confirm.required'],
      ),
    ).toBeInTheDocument();
    expect(registerDevice).not.toHaveBeenCalled();
  });

  it('registers a confirmed short code with the leading zeros intact', async () => {
    registerDevice.mockResolvedValue({
      kind: 'created',
      deviceName: 'ZCT000001',
      customerId: 3,
    });
    const { onRegistered, onClose } = setup();
    typeCode('000001');
    await pickCustomer('A 油站');
    fireEvent.click(screen.getByRole('checkbox'));
    submit();
    await waitFor(() => expect(onRegistered).toHaveBeenCalledTimes(1));
    expect(registerDevice).toHaveBeenCalledWith({
      deviceName: 'ZCT000001',
      customerId: 3,
    });
    expect(onClose).toHaveBeenCalled();
    expect(message.success).toHaveBeenCalledWith(
      expect.stringContaining('无需等待扫码'),
    );
    expect(message.success).toHaveBeenCalledWith(
      expect.stringContaining('A 油站'),
    );
  });

  it('registers a full code directly, without a confirmation step', async () => {
    registerDevice.mockResolvedValue({
      kind: 'created',
      deviceName: 'ZCT000002',
      customerId: 4,
    });
    setup();
    typeCode('zct000002');
    await pickCustomer('B 油站');
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
    submit();
    await waitFor(() =>
      expect(registerDevice).toHaveBeenCalledWith({
        deviceName: 'ZCT000002',
        customerId: 4,
      }),
    );
  });

  it('tells an existing registration apart from a new one', async () => {
    registerDevice.mockResolvedValue({
      kind: 'exists',
      deviceName: 'ZCT000001',
      customerId: 3,
    });
    const { onRegistered } = setup();
    typeCode('ZCT000001');
    await pickCustomer('A 油站');
    submit();
    await waitFor(() => expect(onRegistered).toHaveBeenCalled());
    expect(message.success).toHaveBeenCalledWith(
      expect.stringContaining('无需重复登记'),
    );
  });

  it('passes the registered device and customer to onRegistered', async () => {
    registerDevice.mockResolvedValue({
      kind: 'created',
      deviceName: 'ZCT000001',
      customerId: 3,
    });
    const { onRegistered } = setup();
    typeCode('ZCT000001');
    await pickCustomer('A 油站');
    submit();
    await waitFor(() =>
      expect(onRegistered).toHaveBeenCalledWith({
        deviceName: 'ZCT000001',
        customerId: 3,
      }),
    );
  });

  describe('assign-station action on the success toast', () => {
    const toastContent = () => {
      const arg = (message.success as unknown as ReturnType<typeof vi.fn>).mock
        .calls[0][0];
      return render(<div>{arg.content}</div>);
    };

    it.each(['created', 'exists'] as const)(
      'offers it after a %s registration, and only calls back when clicked',
      async (kind) => {
        registerDevice.mockResolvedValue({
          kind,
          deviceName: 'ZCT000001',
          customerId: 3,
        });
        const { onAssignStation, onRegistered } = setup(true);
        typeCode('ZCT000001');
        await pickCustomer('A 油站');
        submit();
        await waitFor(() => expect(onRegistered).toHaveBeenCalled());
        // 不点就不会有任何分配动作
        expect(onAssignStation).not.toHaveBeenCalled();
        const view = toastContent();
        fireEvent.click(
          view.getByText(
            zhPages['pages.console.stations.devices.action.assign'],
          ),
        );
        expect(onAssignStation).toHaveBeenCalledWith({
          deviceName: 'ZCT000001',
          customerId: 3,
        });
      },
    );

    it('is plain text when no handler is given', async () => {
      registerDevice.mockResolvedValue({
        kind: 'created',
        deviceName: 'ZCT000001',
        customerId: 3,
      });
      const { onRegistered } = setup(false);
      typeCode('ZCT000001');
      await pickCustomer('A 油站');
      submit();
      await waitFor(() => expect(onRegistered).toHaveBeenCalled());
      expect(typeof (message.success as any).mock.calls[0][0]).toBe('string');
    });

    it('does not appear when registration fails with 409', async () => {
      registerDevice.mockRejectedValue(new ApiError(409, 'belongs'));
      const { onAssignStation } = setup(true);
      typeCode('ZCT000001');
      await pickCustomer('A 油站');
      submit();
      await screen.findByText(zhPages['pages.ops.devices.error.conflict']);
      expect(message.success).not.toHaveBeenCalled();
      expect(onAssignStation).not.toHaveBeenCalled();
    });
  });

  it('sends only one request when the form is submitted again while one is pending', async () => {
    let resolve: (v: unknown) => void = () => undefined;
    registerDevice.mockReturnValue(new Promise((r) => (resolve = r)));
    setup();
    typeCode('ZCT000001');
    await pickCustomer('A 油站');
    const form = codeInput().closest('form') as HTMLFormElement;
    fireEvent.submit(form);
    await waitFor(() => expect(registerDevice).toHaveBeenCalledTimes(1));
    // 请求还没回来时再提交（例如在输入框里按回车）：按钮的 loading 拦不住表单提交，
    // 只有 onFinish 里的防重能拦住
    fireEvent.submit(form);
    fireEvent.submit(form);
    await new Promise((r) => setTimeout(r, 50));
    expect(registerDevice).toHaveBeenCalledTimes(1);
    resolve({ kind: 'created', deviceName: 'ZCT000001', customerId: 3 });
  });

  it('keeps the form open on a 409 and explains the conflict', async () => {
    registerDevice.mockRejectedValue(
      new ApiError(409, 'device already belongs'),
    );
    const { onClose, onRegistered } = setup();
    typeCode('ZCT000001');
    await pickCustomer('A 油站');
    submit();
    expect(
      await screen.findByText(zhPages['pages.ops.devices.error.conflict']),
    ).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
    expect(onRegistered).not.toHaveBeenCalled();
    expect(codeInput()).toHaveValue('ZCT000001');
  });

  it('shows the backend text on a 400', async () => {
    registerDevice.mockRejectedValue(new ApiError(400, 'unknown customerId'));
    setup();
    typeCode('ZCT000001');
    await pickCustomer('A 油站');
    submit();
    expect(await screen.findByText(/unknown customerId/)).toBeInTheDocument();
  });

  it('says there is no permission on a 403', async () => {
    registerDevice.mockRejectedValue(
      new ApiError(403, 'only a platform admin'),
    );
    setup();
    typeCode('ZCT000001');
    await pickCustomer('A 油站');
    submit();
    expect(
      await screen.findByText(zhPages['pages.ops.devices.error.forbidden']),
    ).toBeInTheDocument();
  });

  it('stays silent on a 401, which is already redirecting to login', async () => {
    registerDevice.mockRejectedValue(new ApiError(401, undefined, true));
    const { onClose } = setup();
    typeCode('ZCT000001');
    await pickCustomer('A 油站');
    submit();
    await waitFor(() => expect(registerDevice).toHaveBeenCalled());
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.queryByText(/操作失败/)).not.toBeInTheDocument();
  });

  it('stays silent when the account was revoked (403 handled as an auth failure)', async () => {
    registerDevice.mockRejectedValue(new ApiError(403, 'not authorized', true));
    const { onClose } = setup();
    typeCode('ZCT000001');
    await pickCustomer('A 油站');
    submit();
    await waitFor(() => expect(registerDevice).toHaveBeenCalled());
    expect(onClose).not.toHaveBeenCalled();
    expect(
      screen.queryByText(zhPages['pages.ops.devices.error.forbidden']),
    ).not.toBeInTheDocument();
  });

  it('lets the same form submit again after a failure', async () => {
    registerDevice.mockRejectedValueOnce(new ApiError(0));
    registerDevice.mockResolvedValueOnce({
      kind: 'created',
      deviceName: 'ZCT000001',
      customerId: 3,
    });
    const { onRegistered } = setup();
    typeCode('ZCT000001');
    await pickCustomer('A 油站');
    submit();
    expect(
      await screen.findByText(zhPages['pages.ops.devices.error.network']),
    ).toBeInTheDocument();
    submit();
    await waitFor(() => expect(onRegistered).toHaveBeenCalled());
    expect(registerDevice).toHaveBeenCalledTimes(2);
  });

  describe('creating a customer inline', () => {
    const openCreate = async () => {
      fireEvent.mouseDown(await screen.findByRole('combobox'));
      return screen.findByPlaceholderText(
        zhPages['pages.ops.devices.form.customer.new.placeholder'],
      );
    };
    const createButton = () =>
      screen.getByRole('button', {
        name: new RegExp(zhPages['pages.ops.devices.form.customer.new.button']),
      });

    it('refreshes the list, selects the new customer and keeps the typed code', async () => {
      const created = {
        id: 9,
        name: 'C 油站',
        createdAt: '2026-10-07T00:00:00Z',
      };
      createCustomer.mockResolvedValue(created);
      listCustomers
        .mockResolvedValueOnce(customers)
        .mockResolvedValueOnce([...customers, created]);
      registerDevice.mockResolvedValue({
        kind: 'created',
        deviceName: 'ZCT000001',
        customerId: 9,
      });
      setup();
      typeCode('ZCT000001');
      const input = await openCreate();
      fireEvent.change(input, { target: { value: 'C 油站' } });
      fireEvent.click(createButton());
      await waitFor(() =>
        expect(createCustomer).toHaveBeenCalledWith({ name: 'C 油站' }),
      );
      await waitFor(() => expect(listCustomers).toHaveBeenCalledTimes(2));
      expect(codeInput()).toHaveValue('ZCT000001');
      submit();
      await waitFor(() =>
        expect(registerDevice).toHaveBeenCalledWith({
          deviceName: 'ZCT000001',
          customerId: 9,
        }),
      );
    });

    it('explains a name conflict and creates nothing else', async () => {
      createCustomer.mockRejectedValue(new ApiError(409, 'exists'));
      setup();
      typeCode('ZCT000001');
      const input = await openCreate();
      fireEvent.change(input, { target: { value: 'A 油站' } });
      fireEvent.click(createButton());
      await waitFor(() =>
        expect(message.error).toHaveBeenCalledWith(
          zhPages['pages.ops.devices.customer.error.exists'],
        ),
      );
      expect(listCustomers).toHaveBeenCalledTimes(1);
      expect(codeInput()).toHaveValue('ZCT000001');
    });

    it('does not create a customer with a blank name', async () => {
      setup();
      const input = await openCreate();
      fireEvent.change(input, { target: { value: '   ' } });
      fireEvent.click(createButton());
      expect(createCustomer).not.toHaveBeenCalled();
    });
  });

  it('never calls anything but registerDevice for the registration', async () => {
    registerDevice.mockResolvedValue({
      kind: 'created',
      deviceName: 'ZCT000001',
      customerId: 3,
    });
    setup();
    typeCode('ZCT000001');
    await pickCustomer('A 油站');
    submit();
    await waitFor(() => expect(registerDevice).toHaveBeenCalledTimes(1));
    expect(createCustomer).not.toHaveBeenCalled();
  });
});
