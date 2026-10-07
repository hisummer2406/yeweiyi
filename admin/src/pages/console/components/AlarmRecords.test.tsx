import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import zhPages from '@/locales/zh-CN/pages';

const listAlarms = vi.fn();

vi.mock('@umijs/max', () => ({
  useIntl: () => ({
    formatMessage: ({ id }: { id: string }, values?: Record<string, string>) =>
      ((zhPages as Record<string, string>)[id] ?? id).replace(
        /\{(\w+)\}/g,
        (_, k) => values?.[k] ?? '',
      ),
  }),
}));
vi.mock('@/services/ant-design-pro/alarms', () => ({ listAlarms }));

const { default: AlarmRecords } = await import('./AlarmRecords');
const { ApiError } = await import('@/services/ant-design-pro/apiError');

const alarm = (i: number, p: Partial<API.Alarm> = {}): API.Alarm => ({
  alarmId: `a${i}`,
  tank: 1,
  type: 'HIGH_LEVEL',
  severity: 'CRITICAL',
  status: 'ACTIVE',
  text: `text-${i}`,
  time: new Date(Date.UTC(2026, 9, 6, 0, i)).toISOString(),
  ...p,
});
const list = (n: number, partial = false): API.AlarmList => ({
  rows: Array.from({ length: n }, (_, i) => alarm(i)),
  partial,
});

beforeEach(() => vi.clearAllMocks());
afterEach(cleanup);

describe('AlarmRecords', () => {
  it('requests the given device for the last 24 hours', async () => {
    listAlarms.mockResolvedValue(list(1));
    render(<AlarmRecords device="D1" />);
    await screen.findByText('text-0');
    const q: API.RecordsQuery = listAlarms.mock.calls[0][0];
    expect(q.device).toBe('D1');
    expect(Date.parse(q.endTime) - Date.parse(q.beginTime)).toBe(86_400_000);
  });

  it('shows the Chinese labels of known enums', async () => {
    listAlarms.mockResolvedValue({ rows: [alarm(0)], partial: false });
    render(<AlarmRecords device="D1" />);
    await screen.findByText('text-0');
    expect(screen.getByText('高液位')).toBeTruthy();
    expect(screen.getByText('紧急')).toBeTruthy();
    expect(screen.getByText('报警中')).toBeTruthy();
  });

  it('shows an unknown type as itself, never blank', async () => {
    listAlarms.mockResolvedValue({
      rows: [alarm(0, { type: 'NEW_TYPE' })],
      partial: false,
    });
    render(<AlarmRecords device="D1" />);
    expect(await screen.findByText('NEW_TYPE')).toBeTruthy();
  });

  it('shows -- when an alarm has no value', async () => {
    listAlarms.mockResolvedValue({ rows: [alarm(0)], partial: false });
    render(<AlarmRecords device="D1" />);
    const row = (await screen.findByText('text-0')).closest(
      'tr',
    ) as HTMLElement;
    expect(within(row).getByText('--')).toBeTruthy();
  });

  it('paginates on the client: 45 rows, 20 per page', async () => {
    listAlarms.mockResolvedValue(list(45));
    render(<AlarmRecords device="D1" />);
    await screen.findByText('text-0');
    expect(screen.queryByText('text-20')).toBeNull();
    fireEvent.click(screen.getByTitle('2'));
    expect(await screen.findByText('text-20')).toBeTruthy();
    expect(listAlarms).toHaveBeenCalledTimes(1);
  });

  it('shows no notice for a complete short list', async () => {
    listAlarms.mockResolvedValue(list(37));
    render(<AlarmRecords device="D1" />);
    await screen.findByText('text-0');
    expect(screen.queryByText(/请缩小时间范围/)).toBeNull();
    expect(screen.queryByText(/首次同步尚未完成/)).toBeNull();
  });

  it('warns at exactly 500 rows that more may exist', async () => {
    listAlarms.mockResolvedValue(list(500));
    render(<AlarmRecords device="D1" />);
    expect(
      await screen.findByText(/只显示最新 500 条，请缩小时间范围/),
    ).toBeTruthy();
  });

  it('says the records may be incomplete when the response is partial, and still shows the rows', async () => {
    listAlarms.mockResolvedValue(list(2, true));
    render(<AlarmRecords device="D1" />);
    expect(
      await screen.findByText(/首次同步尚未完成，记录可能不完整/),
    ).toBeTruthy();
    expect(screen.getByText('text-0')).toBeTruthy();
  });

  it('an empty complete window shows the empty state', async () => {
    listAlarms.mockResolvedValue(list(0));
    render(<AlarmRecords device="D1" />);
    expect(await screen.findByText('该时间范围内没有报警')).toBeTruthy();
  });

  it('an empty PARTIAL window does not say there are no alarms', async () => {
    listAlarms.mockResolvedValue(list(0, true));
    render(<AlarmRecords device="D1" />);
    expect(await screen.findByText(/首次同步尚未完成/)).toBeTruthy();
    expect(screen.queryByText('该时间范围内没有报警')).toBeNull();
  });

  it('a failure shows an error with retry, not the empty state', async () => {
    listAlarms.mockRejectedValueOnce(new ApiError(500, 'boom'));
    render(<AlarmRecords device="D1" />);
    expect(await screen.findByText('记录加载失败')).toBeTruthy();
    expect(screen.queryByText('该时间范围内没有报警')).toBeNull();
    listAlarms.mockResolvedValue(list(1));
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(await screen.findByText('text-0')).toBeTruthy();
  });

  it('stays silent when the auth failure was already handled', async () => {
    listAlarms.mockRejectedValue(new ApiError(401, undefined, true));
    render(<AlarmRecords device="D1" />);
    await waitFor(() => expect(listAlarms).toHaveBeenCalled());
    await new Promise((r) => setTimeout(r, 30));
    expect(screen.queryByText('记录加载失败')).toBeNull();
    expect(screen.queryByText('该时间范围内没有报警')).toBeNull();
  });

  it('always shows the note about records from before the transfer', async () => {
    listAlarms.mockResolvedValue(list(0));
    render(<AlarmRecords device="D1" />);
    expect(
      await screen.findByText('设备转移给当前客户之前的记录不会显示。'),
    ).toBeTruthy();
  });

  it('switching device requests the new one and drops the old rows', async () => {
    listAlarms.mockImplementation((q: API.RecordsQuery) =>
      Promise.resolve({
        rows: [alarm(0, { text: `of-${q.device}` })],
        partial: false,
      }),
    );
    const { rerender } = render(<AlarmRecords device="D1" />);
    await screen.findByText('of-D1');
    rerender(<AlarmRecords device="D2" />);
    expect(await screen.findByText('of-D2')).toBeTruthy();
    expect(screen.queryByText('of-D1')).toBeNull();
  });
});
