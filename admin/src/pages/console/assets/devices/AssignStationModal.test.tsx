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

const assignDeviceStation = vi.fn();

vi.mock('@umijs/max', () => ({
  useIntl: () => ({
    formatMessage: ({ id }: { id: string }, values?: Record<string, string>) =>
      ((zhPages as Record<string, string>)[id] ?? id).replace(
        /\{(\w+)\}/g,
        (_, k) => values?.[k] ?? '',
      ),
  }),
}));
vi.mock('@/services/ant-design-pro/stations', () => ({ assignDeviceStation }));

const { default: AssignStationModal } = await import('./AssignStationModal');
const { ApiError } = await import('@/services/ant-design-pro/apiError');

const stations = [
  { id: 3, customerId: 1, name: '甲站', address: '', createdAt: '' },
  { id: 4, customerId: 2, name: '乙站', address: '', createdAt: '' },
];
const device = (over: Partial<API.DeviceRow> = {}): API.DeviceRow => ({
  device_name: 'D1',
  is_online: false,
  last_online_at: null,
  last_offline_at: null,
  updated_at: null,
  customer_id: 1,
  station_id: null,
  ...over,
});

const openSelect = async () => {
  const combo = await screen.findByRole('combobox');
  fireEvent.mouseDown(combo);
  return combo;
};

describe('AssignStationModal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(message, 'success').mockImplementation((() => {}) as never);
    vi.spyOn(message, 'error').mockImplementation((() => {}) as never);
  });
  afterEach(cleanup);

  it("offers only the device's own customer's stations", async () => {
    render(
      <AssignStationModal
        device={device()}
        stations={stations}
        onClose={() => {}}
        onDone={() => {}}
      />,
    );
    await openSelect();
    expect(await screen.findByText('甲站')).toBeTruthy();
    expect(screen.queryByText('乙站')).toBeNull();
  });

  it('shows a no-stations hint and cannot submit when the customer has no station', async () => {
    render(
      <AssignStationModal
        device={device({ customer_id: 9 })}
        stations={stations}
        onClose={() => {}}
        onDone={() => {}}
      />,
    );
    expect(
      await screen.findByText(
        zhPages['pages.console.stations.assign.noStations'],
      ),
    ).toBeTruthy();
    // antd 会在两个汉字的按钮文字中间补空格（「确 认」）
    const ok = screen.getByRole('button', { name: /确\s*认/ });
    expect((ok as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(ok);
    expect(assignDeviceStation).not.toHaveBeenCalled();
  });

  it("does not carry the previous device's station over to the next device", async () => {
    assignDeviceStation.mockResolvedValue({ changed: true });
    const props = { stations, onClose: () => {}, onDone: () => {} };
    const { rerender } = render(
      <AssignStationModal
        {...props}
        device={device({ device_name: 'A', station_id: 3 })}
      />,
    );
    await screen.findByText('甲站');
    // 关掉再为另一台（同客户、没有油站）的设备打开
    rerender(<AssignStationModal {...props} device={undefined} />);
    rerender(
      <AssignStationModal
        {...props}
        device={device({ device_name: 'B', station_id: null })}
      />,
    );
    await screen.findByText(/分配油站：B/);
    expect(screen.queryByText('甲站')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /确\s*认/ }));
    await waitFor(() =>
      expect(assignDeviceStation).toHaveBeenCalledWith('B', null),
    );
  });

  it('does not filter when the row carries no customer_id (customer admin view)', async () => {
    render(
      <AssignStationModal
        device={device({ customer_id: undefined })}
        stations={stations}
        onClose={() => {}}
        onDone={() => {}}
      />,
    );
    await openSelect();
    expect(await screen.findByText('甲站')).toBeTruthy();
    expect(await screen.findByText('乙站')).toBeTruthy();
  });

  it('assigns the chosen station and reports success', async () => {
    assignDeviceStation.mockResolvedValue({
      deviceName: 'D1',
      stationId: 3,
      changed: true,
    });
    const onDone = vi.fn();
    render(
      <AssignStationModal
        device={device()}
        stations={stations}
        onClose={() => {}}
        onDone={onDone}
      />,
    );
    await openSelect();
    fireEvent.click(await screen.findByText('甲站'));
    fireEvent.click(screen.getByRole('button', { name: /确\s*认/ }));
    await waitFor(() =>
      expect(assignDeviceStation).toHaveBeenCalledWith('D1', 3),
    );
    expect(message.success).toHaveBeenCalledWith('设备 D1 已分配到 甲站');
    expect(onDone).toHaveBeenCalled();
  });

  it('clearing the select sends an explicit null', async () => {
    assignDeviceStation.mockResolvedValue({
      deviceName: 'D1',
      stationId: null,
      changed: true,
    });
    render(
      <AssignStationModal
        device={device({ station_id: 3 })}
        stations={stations}
        onClose={() => {}}
        onDone={() => {}}
      />,
    );
    // 预选当前油站
    expect(await screen.findByText('甲站')).toBeTruthy();
    const clear = document.querySelector('.ant-select-clear') as HTMLElement;
    fireEvent.click(clear);
    fireEvent.click(screen.getByRole('button', { name: /确\s*认/ }));
    await waitFor(() =>
      expect(assignDeviceStation).toHaveBeenCalledWith('D1', null),
    );
    expect(message.success).toHaveBeenCalledWith('设备 D1 已移出油站');
  });

  it('changed=false says nothing needed changing', async () => {
    assignDeviceStation.mockResolvedValue({
      deviceName: 'D1',
      stationId: 3,
      changed: false,
    });
    render(
      <AssignStationModal
        device={device({ station_id: 3 })}
        stations={stations}
        onClose={() => {}}
        onDone={() => {}}
      />,
    );
    await screen.findByText('甲站');
    fireEvent.click(screen.getByRole('button', { name: /确\s*认/ }));
    await waitFor(() =>
      expect(message.success).toHaveBeenCalledWith(
        '设备已在该油站，无需更改。',
      ),
    );
  });

  it("400 says the station is not the device customer's, then closes and refreshes the stale list", async () => {
    assignDeviceStation.mockRejectedValue(new ApiError(400, 'x'));
    const onClose = vi.fn();
    const onDone = vi.fn();
    render(
      <AssignStationModal
        device={device({ station_id: 3 })}
        stations={stations}
        onClose={onClose}
        onDone={onDone}
      />,
    );
    await screen.findByText('甲站');
    fireEvent.click(screen.getByRole('button', { name: /确\s*认/ }));
    await waitFor(() =>
      expect(message.error).toHaveBeenCalledWith(
        '该油站不属于设备所属客户，请刷新后重试。',
      ),
    );
    // 过期的页面状态不能让用户在同一份旧选项上反复重试：关弹窗并刷新出真实数据
    expect(onClose).toHaveBeenCalled();
    expect(onDone).toHaveBeenCalled();
  });

  it('stays silent when the auth failure was already handled', async () => {
    assignDeviceStation.mockRejectedValue(new ApiError(401, undefined, true));
    render(
      <AssignStationModal
        device={device({ station_id: 3 })}
        stations={stations}
        onClose={() => {}}
        onDone={() => {}}
      />,
    );
    await screen.findByText('甲站');
    fireEvent.click(screen.getByRole('button', { name: /确\s*认/ }));
    await waitFor(() => expect(assignDeviceStation).toHaveBeenCalled());
    expect(message.error).not.toHaveBeenCalled();
  });

  describe('one station : one device', () => {
    const rows = [
      device({ device_name: 'D1', station_id: null }),
      device({ device_name: '000777', station_id: 3 }),
    ];

    it('disables a station already held by another device and names the occupant', async () => {
      render(
        <AssignStationModal
          device={device()}
          stations={stations}
          devices={rows}
          onClose={() => {}}
          onDone={() => {}}
        />,
      );
      await openSelect();
      const opt = await screen.findByText('甲站（已有设备 000777）');
      expect(
        opt.closest('.ant-select-item')?.getAttribute('aria-disabled'),
      ).toBe('true');
      expect(
        screen.getByText(zhPages['pages.console.stations.assign.occupiedHint']),
      ).toBeTruthy();
      // 点占用项不会选中，也就提交不出覆盖
      fireEvent.click(opt);
      fireEvent.click(screen.getByRole('button', { name: /确\s*认/ }));
      await waitFor(() =>
        expect(assignDeviceStation).toHaveBeenCalledWith('D1', null),
      );
      expect(assignDeviceStation).not.toHaveBeenCalledWith('D1', 3);
    });

    it("does not treat the device's own station as occupied", async () => {
      render(
        <AssignStationModal
          device={device({ device_name: '000777', station_id: 3 })}
          stations={stations}
          devices={rows}
          onClose={() => {}}
          onDone={() => {}}
        />,
      );
      expect(await screen.findByText('甲站')).toBeTruthy();
      expect(screen.queryByText(/已有设备/)).toBeNull();
    });

    it('a server-side 409 (lost race / occupied) explains, closes and refreshes the list', async () => {
      assignDeviceStation.mockRejectedValue(
        new ApiError(409, 'station already has a device'),
      );
      const onDone = vi.fn();
      const onClose = vi.fn();
      render(
        <AssignStationModal
          device={device()}
          stations={stations}
          // 页面列表是过期的：本地看这个油站是空的，云端说已被占用
          devices={[device()]}
          onClose={onClose}
          onDone={onDone}
        />,
      );
      await openSelect();
      fireEvent.click(await screen.findByText('甲站'));
      fireEvent.click(screen.getByRole('button', { name: /确\s*认/ }));
      await waitFor(() =>
        expect(message.error).toHaveBeenCalledWith(
          zhPages['pages.console.stations.assign.error.conflict'],
        ),
      );
      expect(onClose).toHaveBeenCalled();
      expect(onDone).toHaveBeenCalled(); // 刷新出真实状态
      expect(message.success).not.toHaveBeenCalled();
    });

    it('station_occupied names the device holding the station and refreshes', async () => {
      assignDeviceStation.mockRejectedValue(
        new ApiError(409, 'x', false, 'station_occupied', {
          occupiedBy: '000777',
        }),
      );
      const onDone = vi.fn();
      render(
        <AssignStationModal
          device={device()}
          stations={stations}
          devices={[device()]}
          onClose={() => {}}
          onDone={onDone}
        />,
      );
      await openSelect();
      fireEvent.click(await screen.findByText('甲站'));
      fireEvent.click(screen.getByRole('button', { name: /确\s*认/ }));
      await waitFor(() =>
        expect(message.error).toHaveBeenCalledWith(
          '该油站已被设备 000777 占用，不能直接分配；需要更换请在原设备行使用「换机」。列表已刷新。',
        ),
      );
      expect(onDone).toHaveBeenCalled();
    });

    it('a failed auth is not reported again (already redirecting to login)', async () => {
      assignDeviceStation.mockRejectedValue(
        new ApiError(401, 'unauthorized', true),
      );
      const onDone = vi.fn();
      render(
        <AssignStationModal
          device={device()}
          stations={stations}
          devices={[device()]}
          onClose={() => {}}
          onDone={onDone}
        />,
      );
      await openSelect();
      fireEvent.click(await screen.findByText('甲站'));
      fireEvent.click(screen.getByRole('button', { name: /确\s*认/ }));
      await waitFor(() => expect(assignDeviceStation).toHaveBeenCalled());
      expect(message.error).not.toHaveBeenCalled();
      expect(onDone).not.toHaveBeenCalled();
    });
  });
});
