import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import zhPages from '@/locales/zh-CN/pages';

const purgeDevice = vi.fn();
const getDevicePurge = vi.fn();
const listDevicePurges = vi.fn();

vi.mock('@umijs/max', () => ({
  useIntl: () => ({
    formatMessage: (
      { id }: { id: string },
      values?: Record<string, string | number>,
    ) =>
      ((zhPages as Record<string, string>)[id] ?? id).replace(
        /\{(\w+)\}/g,
        (_, k) => String(values?.[k] ?? ''),
      ),
  }),
}));
vi.mock('@/services/ant-design-pro/devices', () => ({
  purgeDevice,
  getDevicePurge,
  listDevicePurges,
}));

const { default: PurgeDeviceModal } = await import('./PurgeDeviceModal');
const { ApiError } = await import('@/services/ant-design-pro/apiError');

const device: API.DeviceRow & { customerName?: string; stationName?: string } =
  {
    device_name: 'ZCT0012',
    is_online: false,
    last_online_at: null,
    last_offline_at: null,
    updated_at: null,
    customer_id: 5,
    station_id: 9,
    customerName: '甲公司',
    stationName: '城东站',
  };

const task = (over: Partial<API.DevicePurge> = {}): API.DevicePurge => ({
  id: 7,
  deviceName: 'ZCT0012',
  status: 'running',
  step: 'thingsboard',
  counts: {},
  requestedBy: { id: 1, username: 'admin' },
  reason: '误登记',
  customerId: 5,
  stationId: 9,
  startedAt: '2026-11-02T08:00:00Z',
  finishedAt: null,
  ...over,
});

const setup = () => {
  const onClose = vi.fn();
  const onDone = vi.fn();
  render(
    <PurgeDeviceModal
      device={device}
      onClose={onClose}
      onDone={onDone}
      pollMs={10}
    />,
  );
  return { onClose, onDone };
};
const submitBtn = () =>
  screen.getByRole('button', { name: /彻底删除/ }) as HTMLButtonElement;
const fill = (confirm: string, reason = '误登记') => {
  fireEvent.change(screen.getByLabelText('删除原因（必填）'), {
    target: { value: reason },
  });
  fireEvent.change(screen.getByLabelText('确认设备编号'), {
    target: { value: confirm },
  });
};

beforeEach(() => {
  vi.clearAllMocks();
  listDevicePurges.mockResolvedValue([]);
});
afterEach(cleanup);

describe('PurgeDeviceModal confirmation', () => {
  it('shows the full code, customer, station and the irreversible warning', async () => {
    setup();
    expect(await screen.findByText('彻底删除设备：ZCT0012')).toBeTruthy();
    expect(screen.getByText('甲公司')).toBeTruthy();
    expect(screen.getByText('城东站')).toBeTruthy();
    expect(
      screen.getByText('将彻底删除该设备及全部历史数据，无法在系统内撤销'),
    ).toBeTruthy();
  });

  it('keeps the button disabled until the code matches exactly and a reason is given', async () => {
    setup();
    await screen.findByText('彻底删除设备：ZCT0012');
    expect(submitBtn().disabled).toBe(true);
    fill('12'); // 少了前缀
    expect(submitBtn().disabled).toBe(true);
    fill('zct0012'); // 大小写不同
    expect(submitBtn().disabled).toBe(true);
    fill('ZCT0012', '   ');
    expect(submitBtn().disabled).toBe(true);
    fill('ZCT0012');
    expect(submitBtn().disabled).toBe(false);
  });

  it('cancel sends nothing', async () => {
    const { onClose, onDone } = setup();
    await screen.findByText('彻底删除设备：ZCT0012');
    fill('ZCT0012');
    fireEvent.click(screen.getByRole('button', { name: /取\s*消/ }));
    expect(onClose).toHaveBeenCalled();
    expect(purgeDevice).not.toHaveBeenCalled();
    expect(onDone).not.toHaveBeenCalled();
  });

  it('submits once even when clicked twice', async () => {
    let resolve!: (p: API.DevicePurge) => void;
    purgeDevice.mockReturnValue(new Promise((r) => (resolve = r)));
    getDevicePurge.mockResolvedValue(task());
    setup();
    await screen.findByText('彻底删除设备：ZCT0012');
    fill('ZCT0012');
    fireEvent.click(submitBtn());
    fireEvent.click(submitBtn());
    expect(purgeDevice).toHaveBeenCalledTimes(1);
    expect(purgeDevice).toHaveBeenCalledWith('ZCT0012', {
      confirmDeviceName: 'ZCT0012',
      reason: '误登记',
    });
    await act(async () => resolve(task()));
  });
});

describe('PurgeDeviceModal progress', () => {
  it('is not "deleted" while running; says so only when the cloud reports completed', async () => {
    purgeDevice.mockResolvedValue(task());
    getDevicePurge
      .mockResolvedValueOnce(task({ step: 'business' }))
      .mockResolvedValue(
        task({
          status: 'completed',
          step: 'done',
          finishedAt: '2026-11-02T08:00:30Z',
          counts: { stockEvents: 310, alarmEvents: 88, telemetryHourly: 0 },
        }),
      );
    const { onDone } = setup();
    await screen.findByText('彻底删除设备：ZCT0012');
    fill('ZCT0012');
    fireEvent.click(submitBtn());

    expect(await screen.findByText(/云端正在删除/)).toBeTruthy();
    expect(screen.queryByText(/已彻底删除/)).toBeNull();
    expect(onDone).toHaveBeenCalled(); // 任务一建立就刷新一次

    expect(await screen.findByText('设备 ZCT0012 已彻底删除')).toBeTruthy();
    expect(screen.getByText(/出入库 310/)).toBeTruthy();
    expect(screen.getByText(/报警 88/)).toBeTruthy();
    expect(screen.queryByText(/小时汇总/)).toBeNull(); // 为 0 的不列
  });

  it('shows the failed step and the cloud error, and retry re-sends the same request', async () => {
    purgeDevice
      .mockResolvedValueOnce(
        task({
          status: 'failed',
          step: 'thingsboard',
          error: 'thingsboard: 503',
        }),
      )
      .mockResolvedValueOnce(task({ status: 'running', step: 'thingsboard' }));
    getDevicePurge.mockResolvedValue(
      task({ status: 'running', step: 'business' }),
    );
    setup();
    await screen.findByText('彻底删除设备：ZCT0012');
    fill('ZCT0012');
    fireEvent.click(submitBtn());

    expect(
      await screen.findByText(/删除未完成：停在「清理 ThingsBoard」/),
    ).toBeTruthy();
    expect(screen.getByText('thingsboard: 503')).toBeTruthy();
    expect(screen.queryByText(/已彻底删除/)).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    await screen.findByText(/云端正在删除/);
    expect(purgeDevice).toHaveBeenCalledTimes(2);
    expect(purgeDevice).toHaveBeenLastCalledWith('ZCT0012', {
      confirmDeviceName: 'ZCT0012',
      reason: '误登记',
    });
  });

  it('picks up an unfinished task when opened, without asking to confirm again', async () => {
    listDevicePurges.mockResolvedValue([
      task({ id: 6, status: 'completed', step: 'done', finishedAt: 'x' }),
      task({ id: 7, status: 'failed', step: 'business', error: 'db down' }),
    ]);
    setup();
    expect(await screen.findByText('db down')).toBeTruthy();
    expect(screen.queryByLabelText('确认设备编号')).toBeNull();
    expect(purgeDevice).not.toHaveBeenCalled();
  });

  it('keeps polling through a failed status read and says so, never calling it done', async () => {
    purgeDevice.mockResolvedValue(task());
    getDevicePurge
      .mockRejectedValueOnce(new ApiError(0))
      .mockResolvedValue(task({ step: 'verify' }));
    setup();
    await screen.findByText('彻底删除设备：ZCT0012');
    fill('ZCT0012');
    fireEvent.click(submitBtn());
    expect(await screen.findByText(/暂时取不到最新状态/)).toBeTruthy();
    await waitFor(() =>
      expect(getDevicePurge.mock.calls.length).toBeGreaterThan(1),
    );
    expect(screen.queryByText(/已彻底删除/)).toBeNull();
  });
});

describe('PurgeDeviceModal errors', () => {
  it('a timeout first looks the task up by device and follows it when found', async () => {
    purgeDevice.mockRejectedValue(new ApiError(0));
    listDevicePurges
      .mockResolvedValueOnce([]) // 打开时
      .mockResolvedValueOnce([task()]); // 超时后找回
    getDevicePurge.mockResolvedValue(task());
    setup();
    await screen.findByText('彻底删除设备：ZCT0012');
    fill('ZCT0012');
    fireEvent.click(submitBtn());
    expect(await screen.findByText(/云端正在删除/)).toBeTruthy();
    expect(purgeDevice).toHaveBeenCalledTimes(1); // 没有自动重发
  });

  it('a timeout with no task found says the result is unknown and does not resend', async () => {
    purgeDevice.mockRejectedValue(new ApiError(0));
    setup();
    await screen.findByText('彻底删除设备：ZCT0012');
    fill('ZCT0012');
    fireEvent.click(submitBtn());
    expect(await screen.findByText(/结果未知/)).toBeTruthy();
    expect(purgeDevice).toHaveBeenCalledTimes(1);
  });

  it.each([
    [400, 'confirm_mismatch', /确认编号与设备编号不一致/],
    [400, 'reason_required', /请填写删除原因/],
    [503, 'thingsboard_unavailable', /未创建删除任务/],
    [403, undefined, /没有权限|无权/],
  ])('%s %s is worded for the user', async (status, code, text) => {
    purgeDevice.mockRejectedValue(
      new ApiError(status as number, 'x', false, code),
    );
    setup();
    await screen.findByText('彻底删除设备：ZCT0012');
    fill('ZCT0012');
    fireEvent.click(submitBtn());
    expect(await screen.findByText(text as RegExp)).toBeTruthy();
  });

  it('404 says the device is gone and refreshes the list', async () => {
    purgeDevice.mockRejectedValue(
      new ApiError(404, 'x', false, 'device_not_found'),
    );
    const { onDone } = setup();
    await screen.findByText('彻底删除设备：ZCT0012');
    fill('ZCT0012');
    fireEvent.click(submitBtn());
    expect(await screen.findByText(/不存在或已被删除/)).toBeTruthy();
    expect(onDone).toHaveBeenCalled();
  });

  it('an auth failure shows nothing (the global handler is already leaving)', async () => {
    purgeDevice.mockRejectedValue(new ApiError(401, 'x', true));
    setup();
    await screen.findByText('彻底删除设备：ZCT0012');
    fill('ZCT0012');
    fireEvent.click(submitBtn());
    await waitFor(() => expect(purgeDevice).toHaveBeenCalled());
    expect(screen.queryByText(/删除失败/)).toBeNull();
  });
});
