import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import TankCard from './TankCard';

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

afterEach(cleanup);

describe('TankCard', () => {
  it('shows the tank, oil type and the measurements', () => {
    const { container } = render(<TankCard tank={tank()} onClick={() => {}} />);
    expect(screen.getByText(/2号 92#/)).toBeTruthy();
    // Statistic 会把数值拆成整数/小数两个节点并加千分位，所以看整体文本
    expect(container.textContent).toContain('1,234.6');
    expect(container.textContent).toContain('21.5');
  });

  it('shows no tags for a normal tank with a connected probe', () => {
    render(<TankCard tank={tank()} onClick={() => {}} />);
    expect(screen.queryByText('报警')).toBeNull();
    expect(screen.queryByText('探针未连接')).toBeNull();
  });

  it('shows the alarm tag and the probe-disconnected tag', () => {
    render(
      <TankCard
        tank={tank({ alarmState: 'ALARM', probeConnected: false })}
        onClick={() => {}}
      />,
    );
    expect(screen.getByText('报警')).toBeTruthy();
    expect(screen.getByText('探针未连接')).toBeTruthy();
  });

  it('shows -- for a missing oil type', () => {
    render(<TankCard tank={tank({ oilType: '' })} onClick={() => {}} />);
    expect(screen.getByText(/2号 --/)).toBeTruthy();
  });

  it('calls onClick when the card is clicked', () => {
    const onClick = vi.fn();
    render(<TankCard tank={tank()} onClick={onClick} />);
    fireEvent.click(screen.getByText(/2号 92#/));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('puts one generic picture on each tank, labelled with its own number', () => {
    render(
      <>
        <TankCard tank={tank({ index: 1 })} onClick={() => {}} />
        <TankCard tank={tank({ index: 3 })} onClick={() => {}} />
      </>,
    );
    const imgs = screen.getAllByRole('img');
    expect(imgs).toHaveLength(2);
    expect(imgs[0].getAttribute('alt')).toBe('1号罐示意图（通用，非实际罐型）');
    expect(imgs[1].getAttribute('alt')).toBe('3号罐示意图（通用，非实际罐型）');
    expect(imgs[0].getAttribute('src')).toBe('/images/tank-generic.png');
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
    expect(screen.getByText('探针未连接')).toBeTruthy();
  });

  it('still navigates when the card is clicked', () => {
    const onClick = vi.fn();
    render(<TankCard tank={tank()} onClick={onClick} />);
    fireEvent.click(screen.getByText(/2号 92#/));
    expect(onClick).toHaveBeenCalled();
  });
});
