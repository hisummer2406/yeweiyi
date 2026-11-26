import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import enPages from '@/locales/en-US/pages';
import zhPages from '@/locales/zh-CN/pages';

let pages: Record<string, string> = zhPages;
vi.mock('@umijs/max', () => ({
  useIntl: () => ({
    formatMessage: ({ id }: { id: string }, v?: Record<string, unknown>) =>
      (pages[id] ?? id).replace(/\{(\w+)\}/g, (_, k) => String(v?.[k] ?? '')),
  }),
}));

const { default: TankCard } = await import('./TankCard');

const tank = (p: Partial<API.Tank> = {}): API.Tank => ({
  index: 2,
  oilType: '92#',
  oilLevelMm: 1234.56,
  waterLevelMm: 3,
  tempC: 21.5,
  oilVolumeL: 8000,
  waterVolumeL: 10,
  oilWeightKg: 6000,
  nullVolumeL: 0,
  oilRatioPct: 50,
  waterRatioPct: 1,
  alarmState: 'NORMAL',
  probeConnected: true,
  ...p,
});

afterEach(() => {
  cleanup();
  pages = zhPages;
});

/** 每行「标签 数值 单位」的纯文本，按显示顺序 */
const rows = () =>
  screen
    .getAllByRole('row')
    .map((r) => Array.from(r.children, (c) => c.textContent).join(' '));

describe('TankCard', () => {
  it('shows the tank, oil type and the measurements', () => {
    render(<TankCard tank={tank()} onClick={() => {}} />);
    expect(screen.getByText('2号 92#')).toBeTruthy();
    // 显示器样式（#21）：固定顺序 液位 → 容积 → 水高 → 温度 → 质量，中文单位，一位小数
    expect(rows()).toEqual([
      '液位 1234.6 毫米',
      '容积 8000.0 升',
      '水高 3.0 毫米',
      '温度 21.5 ℃',
      '质量 6000.0 千克',
    ]);
  });

  it('keeps a real 0 as 0.0, shows -- for missing values, and keeps negative and large numbers whole', () => {
    render(
      <TankCard
        tank={tank({
          waterLevelMm: 0,
          tempC: -12.34,
          oilVolumeL: 1234567.89,
          oilWeightKg: null as unknown as number,
        })}
        onClick={() => {}}
      />,
    );
    expect(rows()).toEqual([
      '液位 1234.6 毫米',
      '容积 1234567.9 升',
      '水高 0.0 毫米',
      '温度 -12.3 ℃',
      '质量 -- 千克',
    ]);
  });

  it('uses the localized labels and units in other languages', () => {
    pages = enPages;
    render(<TankCard tank={tank()} onClick={() => {}} />);
    expect(screen.getByText('Tank 2 · 92#')).toBeTruthy();
    expect(rows()[0]).toBe('Oil level 1234.6 mm');
    expect(rows()[4]).toBe('Mass 6000.0 kg');
  });

  it('shows no tags for a normal tank with a connected probe', () => {
    render(<TankCard tank={tank()} onClick={() => {}} />);
    expect(screen.queryByText('报警')).toBeNull();
    expect(screen.queryByText('通讯异常')).toBeNull();
    expect(screen.queryByText('上次读数')).toBeNull();
  });

  it('shows the alarm tag, the comm-fault tag and marks the last reading', () => {
    render(
      <TankCard
        tank={tank({ alarmState: 'ALARM', probeConnected: false })}
        onClick={() => {}}
      />,
    );
    expect(screen.getByText('报警')).toBeTruthy();
    expect(screen.getByText('通讯异常')).toBeTruthy();
    expect(screen.getByText('上次读数')).toBeTruthy();
  });

  it('shows dashes and 暂无读数 for a tank never read since boot (#75)', () => {
    render(
      <TankCard
        tank={tank({ hasReading: false, oilLevelMm: 0, tempC: 0 })}
        onClick={() => {}}
      />,
    );
    expect(screen.getByText('暂无读数')).toBeTruthy();
    expect(screen.queryByText('上次读数')).toBeNull();
    expect(screen.queryByText('0.0')).toBeNull();
    expect(screen.getAllByText('--').length).toBe(5);
  });

  it('shows -- for a missing oil type', () => {
    render(<TankCard tank={tank({ oilType: '' })} onClick={() => {}} />);
    expect(screen.getByText('2号 --')).toBeTruthy();
  });

  it('calls onClick when the card is clicked', () => {
    const onClick = vi.fn();
    render(<TankCard tank={tank()} onClick={onClick} />);
    fireEvent.click(screen.getByText('2号 92#'));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('draws a tank without a reported shape as horizontal, with no shape text', () => {
    render(
      <>
        <TankCard tank={tank({ index: 1 })} onClick={() => {}} />
        <TankCard tank={tank({ index: 3 })} onClick={() => {}} />
      </>,
    );
    const imgs = screen.getAllByRole('img');
    expect(imgs).toHaveLength(2);
    expect(imgs[0].getAttribute('alt')).toBe('1号罐示意图（卧罐）');
    expect(imgs[1].getAttribute('alt')).toBe('3号罐示意图（卧罐）');
    expect(imgs[0].getAttribute('src')).toBe('/images/tank-generic.png');
    expect(screen.queryByText('卧罐')).toBeNull();
  });

  it('keeps the picture neutral: no-data, alarm and probe state stay as text', () => {
    render(
      <TankCard
        tank={tank({
          alarmState: 'ALARM',
          probeConnected: false,
          oilLevelMm: Number.NaN,
        })}
        onClick={() => {}}
      />,
    );
    const img = screen.getByRole('img');
    expect(img.getAttribute('style') ?? '').not.toMatch(/filter|color/);
    expect(screen.getByText('报警')).toBeTruthy();
    expect(screen.getByText('通讯异常')).toBeTruthy();
    expect(screen.getByText('上次读数')).toBeTruthy();
  });

  it('still navigates when the card is clicked', () => {
    const onClick = vi.fn();
    render(<TankCard tank={tank()} onClick={onClick} />);
    fireEvent.click(screen.getByText('2号 92#'));
    expect(onClick).toHaveBeenCalled();
  });
});
