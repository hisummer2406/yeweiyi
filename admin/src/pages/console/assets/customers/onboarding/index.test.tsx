import {
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

const getCustomerOnboarding = vi.fn();
const completeCustomerOnboarding = vi.fn();
const listCustomers = vi.fn();
const listStations = vi.fn();
const createStation = vi.fn();
const assignDeviceStation = vi.fn();
const registerDevice = vi.fn();
const createUser = vi.fn();
const push = vi.fn();

vi.mock('@umijs/max', () => ({
  history: { push },
  useParams: () => ({ id: '7' }),
  useIntl: () => ({
    formatMessage: ({ id }: { id: string }, values?: Record<string, string>) =>
      ((zhPages as Record<string, string>)[id] ?? id).replace(
        /\{(\w+)\}/g,
        (_, k) => values?.[k] ?? '',
      ),
  }),
}));
vi.mock('@/services/ant-design-pro/customers', () => ({
  getCustomerOnboarding,
  completeCustomerOnboarding,
  listCustomers,
}));
vi.mock('@/services/ant-design-pro/stations', () => ({
  listStations,
  createStation,
  assignDeviceStation,
}));
vi.mock('@/services/ant-design-pro/devices', () => ({ registerDevice }));
vi.mock('@/services/ant-design-pro/users', () => ({ createUser }));

const { default: Wizard } = await import('./index');
const { ApiError } = await import('@/services/ant-design-pro/apiError');

const progress = (over: Partial<API.CustomerOnboarding> = {}) => ({
  customerId: 7,
  status: 'configuring' as const,
  complete: false,
  missing: ['customer_admin', 'station', 'device_in_station'] as const,
  counts: { customerAdmins: 0, stations: 0, devicesInStation: 0 },
  ...over,
});

describe('CustomerOnboarding wizard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listCustomers.mockResolvedValue([
      { id: 7, name: 'A 油站', createdAt: '', onboardingStatus: 'configuring' },
    ]);
    listStations.mockResolvedValue([]);
    getCustomerOnboarding.mockResolvedValue(progress());
    vi.spyOn(message, 'success').mockImplementation((() => {}) as never);
    vi.spyOn(message, 'error').mockImplementation((() => {}) as never);
  });
  afterEach(cleanup);

  it('restores progress from the cloud and keeps complete disabled while missing', async () => {
    getCustomerOnboarding.mockResolvedValue(
      progress({
        missing: ['device_in_station'],
        counts: { customerAdmins: 1, stations: 1, devicesInStation: 0 },
      }),
    );
    listStations.mockResolvedValue([
      { id: 3, customerId: 7, name: '一号站' },
      { id: 4, customerId: 8, name: '别家站' },
    ]);
    render(<Wizard />);
    expect(await screen.findByText(/已有 1 个未停用的客户管理员/)).toBeTruthy();
    expect(screen.getByText(/已有 1 个油站/)).toBeTruthy();
    const btn = screen.getByRole('button', { name: /完成开通/ });
    expect((btn as HTMLButtonElement).disabled).toBe(true);
  });

  it('completes onboarding when the cloud says complete', async () => {
    getCustomerOnboarding.mockResolvedValue(
      progress({
        complete: true,
        missing: [],
        counts: { customerAdmins: 1, stations: 1, devicesInStation: 1 },
      }),
    );
    completeCustomerOnboarding.mockResolvedValue(
      progress({ status: 'active', complete: true, missing: [] }),
    );
    render(<Wizard />);
    const btn = await screen.findByRole('button', { name: /完成开通/ });
    fireEvent.click(btn);
    await waitFor(() =>
      expect(completeCustomerOnboarding).toHaveBeenCalledWith(7),
    );
    await waitFor(() => expect(message.success).toHaveBeenCalled());
  });

  it('409 onboarding_incomplete refreshes progress and names what is missing', async () => {
    getCustomerOnboarding
      .mockResolvedValueOnce(
        progress({
          complete: true,
          missing: [],
          counts: { customerAdmins: 1, stations: 1, devicesInStation: 1 },
        }),
      )
      .mockResolvedValue(progress({ missing: ['station'] }));
    completeCustomerOnboarding.mockRejectedValue(
      new ApiError(409, 'incomplete', false, 'onboarding_incomplete'),
    );
    render(<Wizard />);
    fireEvent.click(await screen.findByRole('button', { name: /完成开通/ }));
    await waitFor(() =>
      expect(message.error).toHaveBeenCalledWith('还不能开通，请先完成：油站'),
    );
  });

  it('409 onboarding_incomplete re-reads progress only once', async () => {
    getCustomerOnboarding
      .mockResolvedValueOnce(
        progress({
          complete: true,
          missing: [],
          counts: { customerAdmins: 1, stations: 1, devicesInStation: 1 },
        }),
      )
      .mockResolvedValue(progress({ missing: ['station'] }));
    completeCustomerOnboarding.mockRejectedValue(
      new ApiError(409, 'incomplete', false, 'onboarding_incomplete'),
    );
    render(<Wizard />);
    fireEvent.click(await screen.findByRole('button', { name: /完成开通/ }));
    await waitFor(() => expect(message.error).toHaveBeenCalled());
    expect(getCustomerOnboarding).toHaveBeenCalledTimes(2); // 初次加载 + 一次刷新
  });

  it('a failed stations request is a retryable load error, not "no stations"', async () => {
    getCustomerOnboarding.mockResolvedValue(
      progress({
        missing: ['device_in_station'],
        counts: { customerAdmins: 1, stations: 1, devicesInStation: 0 },
      }),
    );
    listStations.mockRejectedValueOnce(new ApiError(404, 'not found'));
    render(<Wizard />);
    expect(
      await screen.findByText(
        zhPages['pages.ops.customers.onboarding.loadFailed'],
      ),
    ).toBeTruthy();
    // 旧版云端 /api/stations 的 404 不能被当成「客户不存在」
    expect(screen.queryByText('客户不存在，请刷新列表。')).toBeNull();
    expect(
      screen.queryByText(
        zhPages['pages.ops.customers.onboarding.device.noStation'],
      ),
    ).toBeNull();
    // 重试后恢复
    listStations.mockResolvedValue([{ id: 3, customerId: 7, name: '一号站' }]);
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(await screen.findByText(/已有 1 个油站/)).toBeTruthy();
  });

  it('a failed refresh after loading keeps the page instead of replacing it with an error', async () => {
    render(<Wizard />);
    await screen.findByRole('button', { name: /完成开通/ });
    createStation.mockResolvedValue({ id: 9, customerId: 7, name: 'X' });
    getCustomerOnboarding.mockRejectedValue(new ApiError(500, 'boom'));
    fireEvent.click(screen.getByRole('button', { name: /新建油站/ }));
    const name = await screen.findByRole('textbox', { name: /名称/ });
    fireEvent.change(name, { target: { value: 'X' } });
    fireEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', {
        name: /创\s*建/,
      }),
    );
    await waitFor(() => expect(createStation).toHaveBeenCalled());
    await waitFor(() =>
      expect(message.error).toHaveBeenCalledWith(
        zhPages['pages.ops.customers.onboarding.loadFailed'],
      ),
    );
    expect(screen.getByRole('button', { name: /完成开通/ })).toBeTruthy();
  });

  it('shows an active customer without the complete button', async () => {
    getCustomerOnboarding.mockResolvedValue(
      progress({ status: 'active', complete: true, missing: [] }),
    );
    render(<Wizard />);
    expect(await screen.findByText(/该客户已开通/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: /完成开通/ })).toBeNull();
  });

  it('404 customer_not_found shows the not-found result', async () => {
    getCustomerOnboarding.mockRejectedValue(
      new ApiError(404, undefined, false, 'customer_not_found'),
    );
    render(<Wizard />);
    expect(await screen.findByText('客户不存在，请刷新列表。')).toBeTruthy();
  });
});
