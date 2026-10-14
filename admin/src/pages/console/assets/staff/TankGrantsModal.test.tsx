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

const getDeviceTanks = vi.fn();
const listUserTankGrants = vi.fn();
const setUserTankGrants = vi.fn();

vi.mock('@umijs/max', () => ({
  useIntl: () => ({
    formatMessage: ({ id }: { id: string }, values?: Record<string, string>) =>
      ((zhPages as Record<string, string>)[id] ?? id).replace(
        /\{(\w+)\}/g,
        (_, k) => values?.[k] ?? '',
      ),
  }),
}));
vi.mock('@/services/ant-design-pro/tankGrants', () => ({
  getDeviceTanks,
  listUserTankGrants,
  setUserTankGrants,
}));

const { default: TankGrantsModal } = await import('./TankGrantsModal');
const { ApiError } = await import('@/services/ant-design-pro/apiError');

const user: API.AdminUser = {
  id: 4,
  username: null,
  phone: '139****0001',
  role: 'customer_user',
  customerId: 2,
  disabled: false,
  createdAt: '',
};
const dev = (
  name: string,
  customer_id: number | null | undefined,
  station_id: number | null,
): API.DeviceRow => ({
  device_name: name,
  customer_id,
  station_id,
  is_online: false,
  last_online_at: null,
  last_offline_at: null,
  updated_at: null,
});
const stations: API.Station[] = [
  { id: 5, customerId: 2, name: 'G63站', address: '', createdAt: '' },
];
const devices = [
  dev('ZCT930001', 2, 5),
  dev('ZCT930002', 2, null),
  dev('ZCT900003', 3, null), // 别的客户：不可选
  dev('ZCT940001', null, null), // 没有客户：不可选
];

const renderModal = (
  over: Partial<Parameters<typeof TankGrantsModal>[0]> = {},
) => {
  const onClose = vi.fn();
  render(
    <TankGrantsModal
      user={user}
      devices={devices}
      stations={stations}
      onClose={onClose}
      {...over}
    />,
  );
  return { onClose };
};
const saveBtn = () => screen.getAllByRole('button', { name: /保\s*存/ })[0];

beforeEach(() => {
  vi.clearAllMocks();
  listUserTankGrants.mockResolvedValue({
    userId: 4,
    grants: [{ deviceName: 'ZCT930001', tanks: [1] }],
  });
  getDeviceTanks.mockImplementation((d: string) =>
    Promise.resolve(
      d === 'ZCT930001'
        ? { deviceName: d, reported: true, tanks: [{ index: 1 }, { index: 2 }] }
        : { deviceName: d, reported: false, tanks: [] },
    ),
  );
  vi.spyOn(message, 'success').mockImplementation((() => {}) as never);
  vi.spyOn(message, 'error').mockImplementation((() => {}) as never);
});
afterEach(cleanup);

describe('TankGrantsModal', () => {
  it('groups by station, only the same customer, and echoes existing grants', async () => {
    renderModal();
    expect(await screen.findByText('G63站')).toBeTruthy();
    expect(screen.getByText('ZCT930001')).toBeTruthy();
    expect(screen.getByText('ZCT930002')).toBeTruthy();
    expect(screen.queryByText('ZCT900003')).toBeNull();
    expect(screen.queryByText('ZCT940001')).toBeNull();
    expect(screen.getByText('已授权 1 个罐')).toBeTruthy();
    // 已授权设备默认展开，罐1 已勾选、罐2 未勾选（不自动全选）
    const boxes = await screen.findAllByRole('checkbox');
    expect((boxes[0] as HTMLInputElement).checked).toBe(true);
    expect((boxes[1] as HTMLInputElement).checked).toBe(false);
  });

  it('saves the whole selected set for that one device (device_name + tank numbers)', async () => {
    setUserTankGrants.mockResolvedValue({
      userId: 4,
      deviceName: 'ZCT930001',
      tanks: [1, 2],
      changed: true,
    });
    renderModal();
    const boxes = await screen.findAllByRole('checkbox');
    fireEvent.click(boxes[1]); // 勾上罐2
    fireEvent.click(saveBtn());
    await waitFor(() =>
      expect(setUserTankGrants).toHaveBeenCalledWith(4, 'ZCT930001', [1, 2]),
    );
    expect(message.success).toHaveBeenCalledWith(
      '设备 ZCT930001 的油罐授权已保存。',
    );
  });

  it('unticking everything revokes with an explicit empty set and says so', async () => {
    setUserTankGrants.mockResolvedValue({
      userId: 4,
      deviceName: 'ZCT930001',
      tanks: [],
      changed: true,
    });
    renderModal();
    const boxes = await screen.findAllByRole('checkbox');
    fireEvent.click(boxes[0]); // 取消罐1
    expect(
      screen.getByText(zhPages['pages.console.staff.emptyMeansNone']),
    ).toBeTruthy();
    fireEvent.click(saveBtn());
    await waitFor(() =>
      expect(setUserTankGrants).toHaveBeenCalledWith(4, 'ZCT930001', []),
    );
  });

  it('save is disabled while the selection equals the saved set (no no-op PUT)', async () => {
    renderModal();
    await screen.findAllByRole('checkbox');
    expect((saveBtn() as HTMLButtonElement).disabled).toBe(true);
  });

  it('a never-reported device cannot get new grants (nothing to tick), only a notice', async () => {
    renderModal();
    // 展开设备2
    fireEvent.click(await screen.findByText('ZCT930002'));
    expect(
      await screen.findByText(zhPages['pages.console.staff.tanks.notReported']),
    ).toBeTruthy();
  });

  it('server says tanks_unknown/invalid_tank (stale page): explains and re-reads the device tanks', async () => {
    setUserTankGrants.mockRejectedValue(
      new ApiError(400, 'x', false, 'invalid_tank'),
    );
    renderModal();
    const boxes = await screen.findAllByRole('checkbox');
    fireEvent.click(boxes[1]);
    const before = getDeviceTanks.mock.calls.length;
    fireEvent.click(saveBtn());
    await waitFor(() =>
      expect(message.error).toHaveBeenCalledWith(
        zhPages['pages.console.staff.error.invalidTank'],
      ),
    );
    await waitFor(() =>
      expect(getDeviceTanks.mock.calls.length).toBeGreaterThan(before),
    );
  });

  it('user_not_found closes the dialog (staff was removed or moved)', async () => {
    setUserTankGrants.mockRejectedValue(
      new ApiError(404, 'x', false, 'user_not_found'),
    );
    const { onClose } = renderModal();
    const boxes = await screen.findAllByRole('checkbox');
    fireEvent.click(boxes[1]);
    fireEvent.click(saveBtn());
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(message.error).toHaveBeenCalledWith(
      zhPages['pages.console.staff.error.userNotFound'],
    );
  });

  it('an auth failure is not reported again', async () => {
    setUserTankGrants.mockRejectedValue(new ApiError(401, 'x', true));
    renderModal();
    const boxes = await screen.findAllByRole('checkbox');
    fireEvent.click(boxes[1]);
    fireEvent.click(saveBtn());
    await waitFor(() => expect(setUserTankGrants).toHaveBeenCalled());
    expect(message.error).not.toHaveBeenCalled();
  });

  it('grants on devices that are no longer in the list can still be revoked', async () => {
    listUserTankGrants.mockResolvedValue({
      userId: 4,
      grants: [{ deviceName: 'ZCT777777', tanks: [1, 2] }],
    });
    setUserTankGrants.mockResolvedValue({
      userId: 4,
      deviceName: 'ZCT777777',
      tanks: [],
      changed: true,
    });
    renderModal();
    expect(await screen.findByText('ZCT777777')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /撤\s*销/ }));
    await waitFor(() =>
      expect(setUserTankGrants).toHaveBeenCalledWith(4, 'ZCT777777', []),
    );
  });

  it('a customer admin row without customer_id is not filtered out', async () => {
    renderModal({ devices: [dev('ZCT930001', undefined, 5)] });
    expect(await screen.findByText('ZCT930001')).toBeTruthy();
  });
});
