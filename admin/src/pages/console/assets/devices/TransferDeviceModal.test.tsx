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

const assignDeviceCustomer = vi.fn();

vi.mock('@umijs/max', () => ({
  useIntl: () => ({
    formatMessage: ({ id }: { id: string }, values?: Record<string, string>) =>
      ((zhPages as Record<string, string>)[id] ?? id).replace(
        /\{(\w+)\}/g,
        (_, k) => values?.[k] ?? '',
      ),
  }),
}));
vi.mock('@/services/ant-design-pro/devices', () => ({ assignDeviceCustomer }));

const { default: TransferDeviceModal } = await import('./TransferDeviceModal');
const { ApiError } = await import('@/services/ant-design-pro/apiError');

const customers = [
  { id: 1, name: 'A 油站', createdAt: '' },
  { id: 2, name: 'B 油站', createdAt: '' },
];
const row = (customer_id?: number | null): API.DeviceRow => ({
  device_name: 'ZCT000001',
  is_online: false,
  last_online_at: null,
  last_offline_at: null,
  updated_at: null,
  customer_id,
});

const setup = (device: API.DeviceRow) => {
  const onClose = vi.fn();
  const onDone = vi.fn();
  render(
    <TransferDeviceModal
      device={device}
      customers={customers}
      onClose={onClose}
      onDone={onDone}
    />,
  );
  return { onClose, onDone };
};
const pick = async (name: string) => {
  fireEvent.mouseDown(screen.getByLabelText('转移到客户'));
  fireEvent.click(await screen.findByText(name));
};
const confirm = () =>
  fireEvent.click(screen.getByRole('button', { name: /确\s*认/ }));

beforeEach(() => {
  vi.clearAllMocks();
  vi.spyOn(message, 'success').mockImplementation((() => {}) as never);
  vi.spyOn(message, 'error').mockImplementation((() => {}) as never);
});
afterEach(cleanup);

describe('TransferDeviceModal', () => {
  it('offers every customer except the current one and transfers', async () => {
    assignDeviceCustomer.mockResolvedValue({
      deviceName: 'ZCT000001',
      customerId: 2,
      changed: true,
      bindingsCleared: 1,
    });
    const { onDone } = setup(row(1));
    fireEvent.mouseDown(screen.getByLabelText('转移到客户'));
    expect(await screen.findByText('B 油站')).toBeTruthy();
    expect(screen.queryByText('A 油站')).toBeNull();
    fireEvent.click(screen.getByText('B 油站'));
    confirm();
    await waitFor(() =>
      expect(assignDeviceCustomer).toHaveBeenCalledWith('ZCT000001', 2),
    );
    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(message.success).toHaveBeenCalledWith(
      '设备 ZCT000001 已转移到 B 油站',
    );
  });

  it('warns that bindings are cleared when the device already has a customer', () => {
    setup(row(1));
    expect(screen.getByText(/清掉该设备已有的所有绑定/)).toBeTruthy();
  });

  it('an unknown owner (older cloud) is warned about too', () => {
    setup(row(undefined));
    expect(screen.getByText(/清掉该设备已有的所有绑定/)).toBeTruthy();
  });

  it('an unassigned device is assigned without the bindings warning', () => {
    setup(row(null));
    expect(screen.queryByText(/清掉该设备已有的所有绑定/)).toBeNull();
    expect(screen.getByText(/分配客户：ZCT000001/)).toBeTruthy();
  });

  it('requires a customer', async () => {
    setup(row(1));
    confirm();
    expect(await screen.findByText('请选择客户')).toBeTruthy();
    expect(assignDeviceCustomer).not.toHaveBeenCalled();
  });

  it('reports a no-op when the device already belongs to the customer', async () => {
    assignDeviceCustomer.mockResolvedValue({
      deviceName: 'ZCT000001',
      customerId: 2,
      changed: false,
      bindingsCleared: 0,
    });
    setup(row(undefined));
    await pick('B 油站');
    confirm();
    await waitFor(() =>
      expect(message.success).toHaveBeenCalledWith(
        '该设备本来就属于 B 油站，未做改动',
      ),
    );
  });

  it('404 shows the not-found message and keeps the dialog open', async () => {
    assignDeviceCustomer.mockRejectedValue(
      new ApiError(404, 'device not found'),
    );
    const { onDone, onClose } = setup(row(1));
    await pick('B 油站');
    confirm();
    await waitFor(() =>
      expect(message.error).toHaveBeenCalledWith('设备不存在，请刷新列表。'),
    );
    expect(onDone).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('stays silent when the auth failure was already handled', async () => {
    assignDeviceCustomer.mockRejectedValue(new ApiError(401, undefined, true));
    setup(row(1));
    await pick('B 油站');
    confirm();
    await waitFor(() => expect(assignDeviceCustomer).toHaveBeenCalled());
    expect(message.error).not.toHaveBeenCalled();
  });

  it('a second form submit while pending does not transfer twice', async () => {
    let resolve: (v: unknown) => void = () => {};
    assignDeviceCustomer.mockReturnValue(new Promise((r) => (resolve = r)));
    setup(row(1));
    await pick('B 油站');
    const form = screen
      .getByLabelText('转移到客户')
      .closest('form') as HTMLElement;
    fireEvent.submit(form);
    await waitFor(() => expect(assignDeviceCustomer).toHaveBeenCalledTimes(1));
    fireEvent.submit(form);
    fireEvent.submit(form);
    await new Promise((r) => setTimeout(r, 50));
    expect(assignDeviceCustomer).toHaveBeenCalledTimes(1);
    resolve({
      deviceName: 'ZCT000001',
      customerId: 2,
      changed: true,
      bindingsCleared: 0,
    });
  });
});
