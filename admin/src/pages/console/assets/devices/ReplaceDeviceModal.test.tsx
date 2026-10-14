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

const requestMock = vi.fn();

// 用真实的 replaceStationDevice / ReplaceStationDeviceError，只替换底层 HTTP
vi.mock('@/requestErrorConfig', () => ({
  forwardAuthFailure: (e: { response?: { status?: number } }) =>
    e?.response?.status === 401,
}));
vi.mock('@umijs/max', () => {
  const actual = {
    useIntl: () => ({
      formatMessage: (
        { id }: { id: string },
        values?: Record<string, string>,
      ) =>
        ((zhPages as Record<string, string>)[id] ?? id).replace(
          /\{(\w+)\}/g,
          (_, k) => values?.[k] ?? '',
        ),
    }),
    request: (...a: unknown[]) => requestMock(...a),
  };
  return actual;
});

const { default: ReplaceDeviceModal } = await import('./ReplaceDeviceModal');

const stations = [
  { id: 3, customerId: 1, name: '甲站', address: '', createdAt: '' },
];
const dev = (
  device_name: string,
  station_id: number | null | undefined,
  customer_id: number | null | undefined = 1,
): API.DeviceRow => ({
  device_name,
  station_id,
  customer_id,
  is_online: false,
  last_online_at: null,
  last_offline_at: null,
  updated_at: null,
});
const OLD = dev('000123', 3);
const devices = [
  OLD,
  dev('ZCT000999', null), // 同客户、未分配油站：可换入
  dev('ZCT000555', null, 2), // 别的客户：不可选
  dev('ZCT000444', 3), // 已在油站：不可选
  dev('ZCT000333', null, null), // 没有客户：不可选
  dev('ZCT000222', undefined), // 旧版云端，状态未知：不可选
];

const reject = (status: number, data: Record<string, unknown> = {}) =>
  Promise.reject({
    response: { status, data: { error: 'rejected', ...data } },
  });

const optionOf = async (code: string) => {
  // antd 虚拟列表会同时渲染一份无障碍副本，取真正的选项节点
  const hits = await screen.findAllByText(code);
  return hits.find((n) => n.closest('.ant-select-item-option')) ?? hits[0];
};
const pickNew = async (code: string) => {
  fireEvent.mouseDown(await screen.findByRole('combobox'));
  fireEvent.click(await optionOf(code));
};
const confirm = () =>
  fireEvent.click(screen.getByRole('button', { name: /确认换机/ }));

describe('ReplaceDeviceModal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    requestMock.mockReset();
    vi.spyOn(message, 'success').mockImplementation((() => {}) as never);
    vi.spyOn(message, 'error').mockImplementation((() => {}) as never);
  });
  afterEach(cleanup);

  const renderModal = (
    over: Partial<Parameters<typeof ReplaceDeviceModal>[0]> = {},
  ) => {
    const onDone = vi.fn();
    const onClose = vi.fn();
    render(
      <ReplaceDeviceModal
        device={OLD}
        devices={devices}
        stations={stations}
        onClose={onClose}
        onDone={onDone}
        {...over}
      />,
    );
    return { onDone, onClose };
  };

  it('shows old code, station and the no-history-continuation notice', async () => {
    renderModal();
    expect(await screen.findByText('000123')).toBeTruthy();
    expect(screen.getAllByText(/甲站/).length).toBeGreaterThan(0);
    expect(
      screen.getByText(zhPages['pages.console.stations.replace.notice']),
    ).toBeTruthy();
    expect(
      zhPages['pages.console.stations.replace.notice'].includes(
        '新设备重新采集，不接续旧设备历史',
      ),
    ).toBe(true);
  });

  it('offers only registered, same-customer, ungrouped devices as the new device', async () => {
    renderModal();
    fireEvent.mouseDown(await screen.findByRole('combobox'));
    expect(await optionOf('ZCT000999')).toBeTruthy();
    for (const code of ['ZCT000555', 'ZCT000444', 'ZCT000333', 'ZCT000222']) {
      expect(screen.queryByText(code)).toBeNull();
    }
  });

  it('with no candidate it says the new device must be registered first and cannot submit', async () => {
    renderModal({ devices: [OLD] });
    expect(
      await screen.findByText(
        zhPages['pages.console.stations.replace.noCandidates'],
      ),
    ).toBeTruthy();
    const ok = screen.getByRole('button', { name: /确认换机/ });
    expect((ok as HTMLButtonElement).disabled).toBe(true);
  });

  it('replaces with ONE contract call carrying the page-seen old code, then refreshes and reports', async () => {
    requestMock.mockResolvedValue({
      stationId: 3,
      deviceName: 'ZCT000999',
      previousDeviceName: '000123',
      changed: true,
    });
    const { onDone, onClose } = renderModal();
    await pickNew('ZCT000999');
    confirm();
    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(requestMock).toHaveBeenCalledTimes(1);
    expect(requestMock.mock.calls[0][0]).toBe('/api/stations/3/replace-device');
    expect(requestMock.mock.calls[0][1].method).toBe('POST');
    expect(requestMock.mock.calls[0][1].data).toEqual({
      deviceName: 'ZCT000999',
      expectedCurrentDeviceName: '000123',
    });
    expect(message.success).toHaveBeenCalledWith(
      '油站 甲站 已由 000123 换为 ZCT000999，新设备从现在起重新采集。',
    );
    expect(onClose).toHaveBeenCalled();
  });

  it('changed:false (idempotent retry) says nothing changed', async () => {
    requestMock.mockResolvedValue({
      stationId: 3,
      deviceName: 'ZCT000999',
      previousDeviceName: null,
      changed: false,
    });
    renderModal();
    await pickNew('ZCT000999');
    confirm();
    await waitFor(() =>
      expect(message.success).toHaveBeenCalledWith(
        '新设备 ZCT000999 本来就是油站 甲站 的当前设备，无需更改。',
      ),
    );
  });

  it.each([
    [
      'station_device_changed (stale page, someone replaced it already)',
      409,
      { code: 'station_device_changed', currentDeviceName: 'ZCT000777' },
      '油站的当前设备已变化（现为 ZCT000777），页面已过期。列表已刷新，请确认后重新换机。',
    ],
    [
      'device_in_other_station',
      409,
      { code: 'device_in_other_station' },
      zhPages['pages.console.stations.replace.error.otherStation'],
    ],
    [
      'device_customer_mismatch',
      409,
      { code: 'device_customer_mismatch' },
      zhPages['pages.console.stations.replace.error.customerMismatch'],
    ],
    [
      'device_without_customer',
      409,
      { code: 'device_without_customer' },
      zhPages['pages.console.stations.assign.error.noCustomer'],
    ],
    [
      'device_not_found (new device not registered)',
      404,
      { code: 'device_not_found' },
      zhPages['pages.console.stations.replace.error.deviceNotFound'],
    ],
    [
      'station_not_found (station deleted meanwhile)',
      404,
      { code: 'station_not_found' },
      zhPages['pages.console.stations.error.notFound'],
    ],
    [
      'same_device',
      400,
      { code: 'same_device' },
      zhPages['pages.console.stations.replace.error.sameDevice'],
    ],
    [
      'old cloud 404 without code (combined hint)',
      404,
      {},
      zhPages['pages.console.stations.replace.error.notFound'],
    ],
  ])(
    '%s: explains, closes and refreshes the list',
    async (_n, status, data, text) => {
      requestMock.mockImplementation(() => reject(status, data));
      const { onDone, onClose } = renderModal();
      await pickNew('ZCT000999');
      confirm();
      await waitFor(() => expect(message.error).toHaveBeenCalledWith(text));
      expect(onDone).toHaveBeenCalled();
      expect(onClose).toHaveBeenCalled();
      expect(message.success).not.toHaveBeenCalled();
      expect(requestMock).toHaveBeenCalledTimes(1); // 云端事务：没有第二步、没有半成品
    },
  );

  it('an auth failure is forwarded, not reported as a replace error', async () => {
    requestMock.mockImplementation(() =>
      Promise.reject({
        response: { status: 401, data: { error: 'unauthorized' } },
      }),
    );
    const { onDone } = renderModal();
    await pickNew('ZCT000999');
    confirm();
    await waitFor(() => expect(requestMock).toHaveBeenCalled());
    await new Promise((r) => setTimeout(r, 0));
    expect(message.error).not.toHaveBeenCalled();
    expect(onDone).not.toHaveBeenCalled();
  });
});
