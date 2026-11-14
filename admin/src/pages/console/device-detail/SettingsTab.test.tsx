import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { message } from 'antd';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import zhPages from '@/locales/zh-CN/pages';
import { ApiError } from '@/services/ant-design-pro/apiError';

const listDevices = vi.fn();
const listStations = vi.fn();
const getTankSettings = vi.fn();
const getDeviceSettings = vi.fn();
const getTankCalibration = vi.fn();
const issueConfigCommand = vi.fn();
const getCommand = vi.fn();
const findCommandByRequestId = vi.fn();
let role = 'customer_admin';
let actorId = 1;

vi.mock('@/utils/auth', () => ({
  getToken: () => 'test-token',
  decodeToken: () => ({ userId: actorId }),
}));

vi.mock('@umijs/max', () => ({
  useAccess: () => ({ canPlatformAdmin: role === 'platform_admin' }),
  useIntl: () => ({
    locale: 'zh-CN',
    formatMessage: ({ id }: { id: string }, values?: Record<string, string>) =>
      ((zhPages as Record<string, string>)[id] ?? id).replace(
        /\{(\w+)\}/g,
        (_, k) => String(values?.[k] ?? ''),
      ),
  }),
}));
vi.mock('@/services/ant-design-pro/devices', () => ({ listDevices }));
vi.mock('@/services/ant-design-pro/stations', () => ({ listStations }));
const getDeviceMediaOptions = vi.hoisted(() => vi.fn());
vi.mock('@/services/ant-design-pro/media', () => ({ getDeviceMediaOptions }));
vi.mock('@/services/ant-design-pro/deviceConfig', () => ({
  getTankSettings,
  getDeviceSettings,
  getTankCalibration,
  issueConfigCommand,
  getCommand,
  findCommandByRequestId,
}));

const { default: SettingsTab } = await import('./SettingsTab');

const tank1: API.TankSettings = {
  tank: 1,
  reportedAt: '2026-11-08T14:00:00Z',
  tankHeightMm: 2500,
  sensorLengthMm: null,
  tankShape: 0,
  oilType: '92#',
  oilTypeIndex: 3,
  alarmHighHighLevelMm: 2300,
  alarmHighLevelMm: 600,
  alarmLowLevelMm: 300,
  alarmLowLowLevelMm: 0,
  alarmHighWaterMm: 50,
  alarmHighTempC: null,
  alarmLowTempC: -10,
  levelCorrectionMm: 0,
  waterCorrectionMm: 0,
  tempCorrectionC: 0,
  density: 0.56,
  capacityL: 30000,
};

const deviceRow = (over: Partial<API.DeviceRow> = {}): API.DeviceRow => ({
  device_name: 'D1',
  is_online: true,
  last_online_at: null,
  last_offline_at: null,
  updated_at: null,
  station_id: 7,
  ...over,
});

const command = (
  over: Partial<API.ControlCommand> = {},
): API.ControlCommand => ({
  commandId: 'c-1',
  deviceName: 'D1',
  method: 'config.setTankSettings',
  args: { tank: 1, alarmHighLevelMm: 610 },
  operator: 'admin',
  reason: 'r',
  status: 'received',
  terminal: false,
  issuedAt: '2026-11-08T14:00:05Z',
  finishedAt: over.status === 'succeeded' ? '2026-11-08T14:00:06Z' : undefined,
  configTarget: 'tank:1',
  ...over,
});

beforeEach(() => {
  getDeviceMediaOptions.mockResolvedValue({
    public: [],
    customer: [],
    customMediaSync: false,
    slotsUsed: 0,
    slotsTotal: 8,
  });
  vi.clearAllMocks();
  // antd 的静态 message 自建渲染根，cleanup 卸不掉，动画定时器会在环境拆除后才触发（CI 上报 window is not defined）
  for (const m of ['success', 'error', 'warning'] as const) {
    vi.spyOn(message, m).mockImplementation((() => {}) as never);
  }
  sessionStorage.clear();
  actorId = 1;
  role = 'customer_admin';
  listDevices.mockResolvedValue([deviceRow()]);
  listStations.mockResolvedValue([{ id: 7, name: '一号站', customer_id: 1 }]);
  getTankSettings.mockResolvedValue([tank1]);
  getDeviceSettings.mockResolvedValue(null);
  getTankCalibration.mockResolvedValue(null);
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

async function editHighLevel(value: string) {
  const input = await screen.findByLabelText('高液位报警');
  fireEvent.change(input, { target: { value } });
  fireEvent.blur(input);
}

async function openConfirm() {
  fireEvent.click(screen.getAllByRole('button', { name: '下发到设备' })[0]);
  return screen.findByRole('dialog');
}

async function confirm(dialog: HTMLElement, reason = '调整') {
  fireEvent.change(within(dialog).getByLabelText('修改原因（必填）'), {
    target: { value: reason },
  });
  await act(async () => {
    fireEvent.click(
      within(dialog).getByRole('button', {
        name: /确认下发|用同一提交编号重试/,
      }),
    );
  });
}

describe('SettingsTab', () => {
  it('sends only the changed field with requestId and the station seen at open', async () => {
    issueConfigCommand.mockResolvedValue({
      command: command(),
      replayed: false,
    });
    render(<SettingsTab device="D1" />);
    await editHighLevel('610');
    const dialog = await openConfirm();
    expect(within(dialog).getByText('一号站')).toBeTruthy();
    expect(within(dialog).getByText('610 mm')).toBeTruthy();
    await confirm(dialog);
    expect(issueConfigCommand).toHaveBeenCalledTimes(1);
    const [device, body] = issueConfigCommand.mock.calls[0];
    expect(device).toBe('D1');
    expect(body.method).toBe('config.setTankSettings');
    expect(body.args).toEqual({ tank: 1, alarmHighLevelMm: 610 });
    expect(body.expectedStationId).toBe(7);
    expect(body.requestId).toMatch(/^[A-Za-z0-9_-]{1,64}$/);
    expect(await screen.findByText('设备已接收，等待执行结果')).toBeTruthy();
  });

  it('keeps a 1000 mm correction through blur, confirm and submit (#28)', async () => {
    issueConfigCommand.mockResolvedValue({
      command: command(),
      replayed: false,
    });
    render(<SettingsTab device="D1" />);
    const input = (await screen.findByLabelText(
      '液位修正',
    )) as HTMLInputElement;
    fireEvent.change(input, { target: { value: '1000' } });
    fireEvent.keyDown(input, { key: 'Enter', keyCode: 13 });
    fireEvent.blur(input);
    expect(input.value).toBe('1000');
    const dialog = await openConfirm();
    expect(within(dialog).getByText('1000 mm')).toBeTruthy();
    await confirm(dialog);
    expect(issueConfigCommand.mock.calls[0][1].args).toEqual({
      tank: 1,
      levelCorrectionMm: 1000,
    });
  });

  it('keeps an out-of-range value, explains it and blocks the submit (#28)', async () => {
    render(<SettingsTab device="D1" />);
    const input = (await screen.findByLabelText(
      '高温报警',
    )) as HTMLInputElement;
    fireEvent.change(input, { target: { value: '120' } });
    fireEvent.blur(input);
    expect(input.value).toBe('120');
    expect(screen.getByText('超出范围（-50～100）')).toBeTruthy();
    expect(
      (
        screen.getAllByRole('button', {
          name: '下发到设备',
        })[0] as HTMLButtonElement
      ).disabled,
    ).toBe(true);
  });

  it('flips a correction with the sign button, no keyboard minus needed (#30)', async () => {
    render(<SettingsTab device="D1" />);
    const input = (await screen.findByLabelText(
      '水位修正',
    )) as HTMLInputElement;
    const sign = screen.getByRole('button', { name: '水位修正 切换正负号' });
    expect((sign as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(input, { target: { value: '1.5' } });
    fireEvent.click(sign);
    expect(input.value).toBe('-1.5');
    fireEvent.click(sign);
    expect(input.value).toBe('1.5');
  });

  it('never asks a customer admin for media options (#27)', async () => {
    role = 'customer_admin';
    render(<SettingsTab device="D1" />);
    await screen.findByLabelText('液位修正');
    expect(getDeviceMediaOptions).not.toHaveBeenCalled();
  });

  it('sends nothing when the confirmation is cancelled', async () => {
    render(<SettingsTab device="D1" />);
    await editHighLevel('610');
    const dialog = await openConfirm();
    fireEvent.click(
      within(dialog).getByRole('button', { name: /取\s*消|Cancel/ }),
    );
    expect(issueConfigCommand).not.toHaveBeenCalled();
  });

  it('stops when the device moved station after the form was opened', async () => {
    render(<SettingsTab device="D1" />);
    await editHighLevel('610');
    const dialog = await openConfirm();
    listDevices.mockResolvedValue([deviceRow({ station_id: 8 })]);
    await confirm(dialog);
    expect(issueConfigCommand).not.toHaveBeenCalled();
  });

  it('does not submit when the last station check fails', async () => {
    render(<SettingsTab device="D1" />);
    await editHighLevel('610');
    const dialog = await openConfirm();
    listDevices.mockRejectedValueOnce(new ApiError(0));
    await confirm(dialog);
    expect(issueConfigCommand).not.toHaveBeenCalled();
    expect(await within(dialog).findByRole('alert')).toBeTruthy();
  });

  it('recovers a timed-out submission by requestId instead of resending', async () => {
    issueConfigCommand.mockRejectedValue(new ApiError(0));
    findCommandByRequestId.mockResolvedValue(command());
    render(<SettingsTab device="D1" />);
    await editHighLevel('610');
    await confirm(await openConfirm());
    const rid = issueConfigCommand.mock.calls[0][1].requestId;
    expect(findCommandByRequestId).toHaveBeenCalledWith('D1', rid);
    expect(issueConfigCommand).toHaveBeenCalledTimes(1);
    expect(await screen.findByText('设备已接收，等待执行结果')).toBeTruthy();
  });

  it('retries an unconfirmed submission with the same requestId', async () => {
    issueConfigCommand.mockRejectedValueOnce(new ApiError(0));
    findCommandByRequestId.mockResolvedValue(undefined);
    render(<SettingsTab device="D1" />);
    await editHighLevel('610');
    const dialog = await openConfirm();
    await confirm(dialog);
    expect(await within(dialog).findByText(/结果未确认/)).toBeTruthy();
    issueConfigCommand.mockResolvedValue({
      command: command(),
      replayed: true,
    });
    await act(async () => {
      fireEvent.click(
        within(dialog).getByRole('button', { name: /用同一提交编号重试/ }),
      );
    });
    expect(issueConfigCommand).toHaveBeenCalledTimes(2);
    expect(issueConfigCommand.mock.calls[1][1].requestId).toBe(
      issueConfigCommand.mock.calls[0][1].requestId,
    );
  });

  it('follows the in-flight command when the target is busy', async () => {
    const err = new ApiError(409, undefined, false, 'config_command_in_flight');
    err.data = { code: 'config_command_in_flight', commandId: 'busy-1' };
    issueConfigCommand.mockRejectedValue(err);
    getCommand.mockResolvedValue(
      command({ commandId: 'busy-1', status: 'executing' }),
    );
    render(<SettingsTab device="D1" />);
    await editHighLevel('610');
    await confirm(await openConfirm());
    expect(getCommand).toHaveBeenCalledWith('D1', 'busy-1');
    expect(await screen.findByText('设备执行中')).toBeTruthy();
  });

  it('restores an unconfirmed submission after remount and retries the original snapshot', async () => {
    issueConfigCommand.mockRejectedValueOnce(new ApiError(0));
    findCommandByRequestId.mockResolvedValue(undefined);
    const first = render(<SettingsTab device="D1" />);
    await editHighLevel('610');
    await confirm(await openConfirm(), '原始原因');
    const original = issueConfigCommand.mock.calls[0][1];
    first.unmount();

    render(<SettingsTab device="D1" />);
    await editHighLevel('620');
    const dialog = await openConfirm();
    expect(await within(dialog).findByText(/结果未确认/)).toBeTruthy();
    expect(within(dialog).getByText('610 mm')).toBeTruthy();
    expect(within(dialog).queryByText('620 mm')).toBeNull();
    expect(within(dialog).getByLabelText('修改原因（必填）')).toHaveValue(
      '原始原因',
    );
    issueConfigCommand.mockResolvedValue({
      command: command(),
      replayed: true,
    });
    await act(async () => {
      fireEvent.click(
        within(dialog).getByRole('button', { name: /用同一提交编号重试/ }),
      );
    });
    expect(issueConfigCommand.mock.calls[1][1]).toEqual(original);
  });

  // #18 F6：旧快照（油站 7）结果未知，设备随后换到油站 8。旧快照不再被前端油站预检挡住、反复恢复；
  // 原样重发同一编号，由云端裁决。
  async function leaveUnconfirmedOnStation7() {
    issueConfigCommand.mockRejectedValueOnce(new ApiError(0));
    findCommandByRequestId.mockResolvedValue(undefined);
    const first = render(<SettingsTab device="D1" />);
    await editHighLevel('610');
    await confirm(await openConfirm());
    const original = issueConfigCommand.mock.calls[0][1];
    first.unmount();
    listDevices.mockResolvedValue([deviceRow({ station_id: 8 })]);
    listStations.mockResolvedValue([
      { id: 7, name: '一号站', customer_id: 1 },
      { id: 8, name: '二号站', customer_id: 1 },
    ]);
    return original;
  }

  it('settles a never-received old snapshot after the device moved station, then allows a fresh confirmation', async () => {
    const original = await leaveUnconfirmedOnStation7();
    const stale = new ApiError(409, undefined, false, 'device_station_changed');
    stale.data = { code: 'device_station_changed', currentStationId: 8 };
    issueConfigCommand.mockRejectedValueOnce(stale);
    render(<SettingsTab device="D1" />);
    await editHighLevel('620');
    const dialog = await openConfirm();
    expect(await within(dialog).findByText(/结果未确认/)).toBeTruthy();
    await act(async () => {
      fireEvent.click(
        within(dialog).getByRole('button', { name: /用同一提交编号重试/ }),
      );
    });
    // 原样重发，没被前端预检拦下
    expect(issueConfigCommand).toHaveBeenCalledTimes(2);
    expect(issueConfigCommand.mock.calls[1][1]).toEqual(original);
    expect(sessionStorage.length).toBe(0);

    // 之后按当前归属（油站 8）重新确认：新编号、新内容
    issueConfigCommand.mockResolvedValue({
      command: command(),
      replayed: false,
    });
    cleanup();
    render(<SettingsTab device="D1" />);
    await editHighLevel('620');
    const fresh = await openConfirm();
    expect(within(fresh).queryByText(/结果未确认/)).toBeNull();
    await confirm(fresh);
    expect(issueConfigCommand).toHaveBeenCalledTimes(3);
    const body = issueConfigCommand.mock.calls[2][1];
    expect(body.requestId).not.toBe(original.requestId);
    expect(body.expectedStationId).toBe(8);
    expect(body.args.alarmHighLevelMm).toBe(620);
  });

  it('tracks an old snapshot the cloud had actually accepted (replay), sending nothing new', async () => {
    const original = await leaveUnconfirmedOnStation7();
    issueConfigCommand.mockResolvedValueOnce({
      command: command(),
      replayed: true,
    });
    render(<SettingsTab device="D1" />);
    await editHighLevel('620');
    const dialog = await openConfirm();
    await act(async () => {
      fireEvent.click(
        within(dialog).getByRole('button', { name: /用同一提交编号重试/ }),
      );
    });
    expect(issueConfigCommand).toHaveBeenCalledTimes(2);
    expect(issueConfigCommand.mock.calls[1][1]).toEqual(original);
    expect(await screen.findByText('设备已接收，等待执行结果')).toBeTruthy();
    expect(sessionStorage.length).toBe(0);
  });

  it('keeps an old snapshot when the answer says nothing about its requestId', async () => {
    await leaveUnconfirmedOnStation7();
    const gate = new ApiError(
      409,
      undefined,
      false,
      'config_commands_disabled',
    );
    gate.data = { code: 'config_commands_disabled', scope: 'deployment' };
    issueConfigCommand.mockRejectedValueOnce(gate);
    render(<SettingsTab device="D1" />);
    await editHighLevel('620');
    const dialog = await openConfirm();
    await act(async () => {
      fireEvent.click(
        within(dialog).getByRole('button', { name: /用同一提交编号重试/ }),
      );
    });
    expect(sessionStorage.length).toBe(1);
  });

  it('keeps an unconfirmed submission isolated from a different account', async () => {
    issueConfigCommand.mockRejectedValueOnce(new ApiError(0));
    findCommandByRequestId.mockResolvedValue(undefined);
    const first = render(<SettingsTab device="D1" />);
    await editHighLevel('610');
    await confirm(await openConfirm());
    const originalId = issueConfigCommand.mock.calls[0][1].requestId;
    first.unmount();
    actorId = 2;
    issueConfigCommand.mockResolvedValue({
      command: command(),
      replayed: false,
    });
    render(<SettingsTab device="D1" />);
    await editHighLevel('620');
    await confirm(await openConfirm());
    expect(issueConfigCommand.mock.calls[1][1].requestId).not.toBe(originalId);
    expect(issueConfigCommand.mock.calls[1][1].args.alarmHighLevelMm).toBe(620);
  });

  it('does not confirm a matching mirror that predates command completion', async () => {
    issueConfigCommand.mockResolvedValue({
      command: command({
        status: 'succeeded',
        terminal: true,
        finishedAt: '2026-11-08T14:00:10Z',
      }),
      replayed: false,
    });
    getTankSettings
      .mockResolvedValueOnce([tank1])
      .mockResolvedValue([
        { ...tank1, alarmHighLevelMm: 610, reportedAt: '2026-11-08T14:00:07Z' },
      ]);
    render(<SettingsTab device="D1" />);
    await editHighLevel('610');
    await confirm(await openConfirm());
    expect(await screen.findByText('执行成功，等待设备回报')).toBeTruthy();
    expect(screen.queryByText('设备回报值已一致')).toBeNull();
  });

  it('says applied-but-waiting until a fresh matching report arrives', async () => {
    issueConfigCommand.mockResolvedValue({
      command: command({ status: 'succeeded', terminal: true }),
      replayed: false,
    });
    // First read: still the old report; later: the device has re-published.
    getTankSettings
      .mockResolvedValueOnce([tank1])
      .mockResolvedValueOnce([tank1])
      .mockResolvedValue([
        { ...tank1, alarmHighLevelMm: 610, reportedAt: '2026-11-08T14:00:07Z' },
      ]);
    render(<SettingsTab device="D1" />);
    await editHighLevel('610');
    await confirm(await openConfirm());
    expect(await screen.findByText('执行成功，等待设备回报')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '刷新回报' }));
    expect(await screen.findByText('设备回报值已一致')).toBeTruthy();
  });

  // 2026-11-09: no per-device opening — an administrator can send right away, and there is no gate toggle.
  it('lets an administrator send without any per-device opening', async () => {
    render(<SettingsTab device="D1" />);
    expect(
      await screen.findAllByRole('button', { name: '下发到设备' }),
    ).toBeTruthy();
    expect(screen.queryByText(/未开放远程配置/)).toBeNull();
    expect(screen.queryByRole('button', { name: '开放远程配置' })).toBeNull();
  });

  it('keeps device settings read-only for a customer admin', async () => {
    getDeviceSettings.mockResolvedValue({
      deviceName: 'D1',
      reportedAt: '2026-11-08T14:00:00Z',
      inStockHeightThresholdMm: 20,
      inStockTimeThresholdSec: 60,
      outStockHeightThresholdMm: 30,
      outStockTimeThresholdSec: 0,
    });
    render(<SettingsTab device="D1" />);
    expect(
      await screen.findByText('整机设置只能由平台管理员修改。'),
    ).toBeTruthy();
    expect(screen.queryByLabelText('入库判定高度')).toBeNull();
    // 0 stays 0; it is "not configured", not missing.
    await waitFor(() => expect(screen.getByText('0 s')).toBeTruthy());
  });

  // Codex review PR #14 (P1): after the station changed, the old draft must not be sendable under the new station.
  it('discards the draft when the station changed under the form', async () => {
    render(<SettingsTab device="D1" />);
    await editHighLevel('610');
    const dialog = await openConfirm();
    listDevices.mockResolvedValue([deviceRow({ station_id: 8 })]);
    await confirm(dialog);
    expect(issueConfigCommand).not.toHaveBeenCalled();
    await waitFor(() =>
      expect(
        (screen.getByLabelText('高液位报警') as HTMLInputElement).value,
      ).toBe(''),
    );
    expect(
      screen
        .getAllByRole('button', { name: '下发到设备' })[0]
        .hasAttribute('disabled'),
    ).toBe(true);
  });

  // Codex review PR #14 (P2): a failed read is not "never reported".
  it('shows a device-settings read failure instead of an empty editable form', async () => {
    role = 'platform_admin';
    getDeviceSettings.mockRejectedValue(
      new ApiError(500, undefined, false, undefined),
    );
    render(<SettingsTab device="D1" />);
    expect(await screen.findByText('读取整机设置失败')).toBeTruthy();
    expect(screen.queryByText('设备尚未上报整机设置。')).toBeNull();
    expect(screen.queryByLabelText('入库判定高度')).toBeNull();
  });
});
