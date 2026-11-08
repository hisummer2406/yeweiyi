import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
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
const setConfigGate = vi.fn();
let role = 'customer_admin';

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
vi.mock('@/services/ant-design-pro/deviceConfig', () => ({
  getTankSettings,
  getDeviceSettings,
  getTankCalibration,
  issueConfigCommand,
  getCommand,
  findCommandByRequestId,
  setConfigGate,
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
  config_commands_enabled: true,
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
  configTarget: 'tank:1',
  ...over,
});

beforeEach(() => {
  vi.clearAllMocks();
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

  it('is read-only for a device that is not enabled', async () => {
    listDevices.mockResolvedValue([
      deviceRow({ config_commands_enabled: false }),
    ]);
    render(<SettingsTab device="D1" />);
    expect(await screen.findByText(/该设备未开放远程配置/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: '下发到设备' })).toBeNull();
    expect(screen.queryByRole('button', { name: '开放远程配置' })).toBeNull();
  });

  it('lets only a platform admin open the gate, with a reason', async () => {
    role = 'platform_admin';
    listDevices.mockResolvedValue([
      deviceRow({ config_commands_enabled: false }),
    ]);
    setConfigGate.mockResolvedValue({
      deviceName: 'D1',
      enabled: true,
      enabledAt: null,
      enabledBy: 1,
    });
    render(<SettingsTab device="D1" />);
    fireEvent.click(
      await screen.findByRole('button', { name: '开放远程配置' }),
    );
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('修改原因（必填）'), {
      target: { value: '真机验证' },
    });
    await act(async () => {
      fireEvent.click(
        within(dialog).getByRole('button', { name: /确\s*定|OK/ }),
      );
    });
    expect(setConfigGate).toHaveBeenCalledWith('D1', true, '真机验证');
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
});
