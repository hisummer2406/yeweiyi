import {
  cleanup,
  fireEvent,
  render,
  screen,
  within,
} from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import zhPages from '@/locales/zh-CN/pages';

let canPlatformAdmin = true;
const listDevices = vi.fn();
const listCustomers = vi.fn();
const listStations = vi.fn();

vi.mock('@umijs/max', () => ({
  useAccess: () => ({ canPlatformAdmin }),
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
vi.mock('@/services/ant-design-pro/stations', () => ({
  listStations,
  assignDeviceStation: vi.fn(),
  replaceStationDevice: vi.fn(),
}));

vi.mock('./RegisterDeviceModal', () => ({
  default: (props: {
    open: boolean;
    onAssignStation: (o: { deviceName: string; customerId: number }) => void;
  }) =>
    props.open ? (
      <button
        type="button"
        onClick={() =>
          props.onAssignStation({ deviceName: 'D-NEW', customerId: 1 })
        }
      >
        register-stub-assign
      </button>
    ) : null,
}));

const { default: AdminDevices } = await import('./index');

const dev = (
  name: string,
  customer_id?: number | null,
  station_id?: number | null,
): API.DeviceRow => ({
  device_name: name,
  is_online: false,
  last_online_at: null,
  last_offline_at: null,
  updated_at: null,
  customer_id,
  station_id,
});
const rowOf = async (name: string) =>
  (await screen.findByText(name)).closest('tr') as HTMLElement;

beforeEach(() => {
  canPlatformAdmin = true;
  vi.clearAllMocks();
  listCustomers.mockResolvedValue([{ id: 1, name: 'A 油站', createdAt: '' }]);
  listStations.mockResolvedValue([]);
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
    // 客户列与油站列在旧版云端下都是「未知」
    expect(within(await rowOf('D-OLD')).getAllByText('未知')).toHaveLength(2);
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

describe('AdminDevices station column', () => {
  beforeEach(() => {
    listStations.mockResolvedValue([
      { id: 3, customerId: 1, name: '甲站', address: '', createdAt: '' },
    ]);
    listDevices.mockResolvedValue([
      dev('D-IN', 1, 3),
      dev('D-NONE', 1, null),
      dev('D-OLD', 1),
      dev('D-GONE', 1, 77),
      dev('D-FREE', null, null),
    ]);
  });

  it('shows the station name, ungrouped, unknown, or #id', async () => {
    render(<AdminDevices />);
    expect(within(await rowOf('D-IN')).getByText('甲站')).toBeTruthy();
    expect(within(await rowOf('D-NONE')).getByText('未分组')).toBeTruthy();
    expect(within(await rowOf('D-OLD')).getByText('未知')).toBeTruthy();
    expect(within(await rowOf('D-GONE')).getByText('#77')).toBeTruthy();
  });

  it('still renders the list with #id when the station list cannot load', async () => {
    listStations.mockRejectedValue(new Error('boom'));
    render(<AdminDevices />);
    expect(within(await rowOf('D-IN')).getByText('#3')).toBeTruthy();
  });

  it('offers Assign station only to devices that have a customer', async () => {
    render(<AdminDevices />);
    expect(within(await rowOf('D-IN')).getByText('分配油站')).toBeTruthy();
    expect(within(await rowOf('D-FREE')).queryByText('分配油站')).toBeNull();
  });

  it('opens the assign dialog titled with the device name', async () => {
    render(<AdminDevices />);
    fireEvent.click(within(await rowOf('D-IN')).getByText('分配油站'));
    expect(await screen.findByText('分配油站：D-IN')).toBeTruthy();
  });
});

describe('AdminDevices as a customer admin', () => {
  beforeEach(() => {
    canPlatformAdmin = false;
    listStations.mockResolvedValue([
      { id: 3, customerId: 1, name: '甲站', address: '', createdAt: '' },
    ]);
    // 客户管理员的行没有 customer_id 字段
    listDevices.mockResolvedValue([dev('D-A', undefined, 3), dev('D-B')]);
  });

  it('has no customer column, no register button and no customer actions', async () => {
    render(<AdminDevices />);
    await rowOf('D-A');
    expect(
      screen.queryByText(zhPages['pages.ops.devices.col.customer']),
    ).toBeNull();
    expect(
      screen.queryByText(zhPages['pages.ops.devices.register']),
    ).toBeNull();
    expect(screen.queryByText('转移客户')).toBeNull();
    expect(screen.queryByText('分配客户')).toBeNull();
    expect(screen.queryByText('未分配')).toBeNull();
  });

  it('never asks for the customer list (the cloud would answer 403)', async () => {
    render(<AdminDevices />);
    await rowOf('D-A');
    expect(listCustomers).not.toHaveBeenCalled();
  });

  it('still offers Assign station, even though the row carries no customer_id', async () => {
    render(<AdminDevices />);
    expect(within(await rowOf('D-A')).getByText('分配油站')).toBeTruthy();
    expect(within(await rowOf('D-B')).getByText('分配油站')).toBeTruthy();
    expect(within(await rowOf('D-A')).getByText('甲站')).toBeTruthy();
  });
});

describe('AdminDevices as a platform admin', () => {
  beforeEach(() => {
    listDevices.mockResolvedValue([dev('D-OWNED', 1, null)]);
    listStations.mockResolvedValue([
      { id: 3, customerId: 1, name: '甲站', address: '', createdAt: '' },
    ]);
  });

  it('has the customer column, the register button and the transfer action', async () => {
    render(<AdminDevices />);
    const row = await rowOf('D-OWNED');
    expect(
      screen.getByText(zhPages['pages.ops.devices.col.customer']),
    ).toBeTruthy();
    expect(
      screen.getByText(zhPages['pages.ops.devices.register']),
    ).toBeTruthy();
    expect(within(row).getByText('转移客户')).toBeTruthy();
    expect(within(row).getByText('分配油站')).toBeTruthy();
  });

  it('opens the assign dialog for the device just registered', async () => {
    render(<AdminDevices />);
    await rowOf('D-OWNED');
    fireEvent.click(screen.getByText(zhPages['pages.ops.devices.register']));
    fireEvent.click(await screen.findByText('register-stub-assign'));
    expect(await screen.findByText('分配油站：D-NEW')).toBeTruthy();
  });
});

describe('AdminDevices replace action (one station : one device)', () => {
  beforeEach(() => {
    listStations.mockResolvedValue([
      { id: 3, customerId: 1, name: '甲站', address: '', createdAt: '' },
    ]);
    listDevices.mockResolvedValue([
      dev('000123', 1, 3),
      dev('ZCT000999', 1, null),
      dev('ZCT000888', 1, undefined),
    ]);
  });

  it('offers 换机 only on a device that is in a station', async () => {
    render(<AdminDevices />);
    expect(within(await rowOf('000123')).getByText('换机')).toBeTruthy();
    expect(within(await rowOf('ZCT000999')).queryByText('换机')).toBeNull();
    // 旧版云端没有 station_id：状态未知，不提供
    expect(within(await rowOf('ZCT000888')).queryByText('换机')).toBeNull();
  });

  it('opens the replace dialog showing the old code and the station', async () => {
    render(<AdminDevices />);
    fireEvent.click(within(await rowOf('000123')).getByText('换机'));
    expect(await screen.findByText('换机：甲站')).toBeTruthy();
    expect(
      screen.getByText(zhPages['pages.console.stations.replace.old']),
    ).toBeTruthy();
  });
});
