import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import zhPages from '@/locales/zh-CN/pages';

const listDevices = vi.fn();
const listCustomers = vi.fn();

vi.mock('@umijs/max', () => ({
  useIntl: () => ({
    formatMessage: ({ id }: { id: string }, values?: Record<string, string>) =>
      ((zhPages as Record<string, string>)[id] ?? id).replace(
        /\{(\w+)\}/g,
        (_, k) => values?.[k] ?? '',
      ),
  }),
}));
vi.mock('@/services/ant-design-pro/devices', () => ({
  listDevices,
  registerDevice: vi.fn(),
  assignDeviceCustomer: vi.fn(),
}));
vi.mock('@/services/ant-design-pro/customers', () => ({
  listCustomers,
  createCustomer: vi.fn(),
}));

const { default: AdminDevices } = await import('./index');

const dev = (name: string, customer_id?: number | null): API.DeviceRow => ({
  device_name: name,
  is_online: false,
  last_online_at: null,
  last_offline_at: null,
  updated_at: null,
  customer_id,
});
const rowOf = async (name: string) =>
  (await screen.findByText(name)).closest('tr') as HTMLElement;

beforeEach(() => {
  vi.clearAllMocks();
  listCustomers.mockResolvedValue([{ id: 1, name: 'A 油站', createdAt: '' }]);
});
afterEach(cleanup);

describe('AdminDevices customer column', () => {
  beforeEach(() =>
    listDevices.mockResolvedValue([
      dev('D-OWNED', 1),
      dev('D-FREE', null),
      dev('D-OLD'),
      dev('D-GONE', 99),
    ]),
  );

  it('shows the customer name, unassigned, unknown, or #id', async () => {
    render(<AdminDevices />);
    expect(within(await rowOf('D-OWNED')).getByText('A 油站')).toBeTruthy();
    expect(within(await rowOf('D-FREE')).getByText('未分配')).toBeTruthy();
    // 字段缺失 ≠ 未分配：旧版云端不带 customer_id
    expect(within(await rowOf('D-OLD')).getByText('未知')).toBeTruthy();
    expect(within(await rowOf('D-GONE')).getByText('#99')).toBeTruthy();
  });

  it('unassigned rows offer Assign, the others Transfer', async () => {
    render(<AdminDevices />);
    expect(within(await rowOf('D-FREE')).getByText('分配客户')).toBeTruthy();
    expect(within(await rowOf('D-OWNED')).getByText('转移客户')).toBeTruthy();
    expect(within(await rowOf('D-OLD')).getByText('转移客户')).toBeTruthy();
  });

  it('falls back to #id when the customer list cannot load', async () => {
    listCustomers.mockRejectedValue(new Error('boom'));
    render(<AdminDevices />);
    expect(within(await rowOf('D-OWNED')).getByText('#1')).toBeTruthy();
  });
});
