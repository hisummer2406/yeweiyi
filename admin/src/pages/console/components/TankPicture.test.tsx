import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import zhPages from '@/locales/zh-CN/pages';

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

import TankPicture from './TankPicture';

const tank = (p: Partial<API.Tank> = {}): API.Tank => ({
  index: 2,
  oilType: '92#',
  oilLevelMm: 1,
  waterLevelMm: 1,
  tempC: 1,
  oilVolumeL: 1,
  waterVolumeL: 1,
  oilWeightKg: 1,
  nullVolumeL: 1,
  oilRatioPct: 60,
  waterRatioPct: 10,
  alarmState: 'NORMAL',
  probeConnected: true,
  ...p,
});
afterEach(cleanup);

const heights = (c: HTMLElement) => ({
  oil: (c.querySelector('[data-fill="oil"]') as HTMLElement)?.style.height,
  water: (c.querySelector('[data-fill="water"]') as HTMLElement)?.style.height,
});

describe('TankPicture liquid level', () => {
  it("draws oil and water heights from the tank's own ratios", () => {
    const { container } = render(
      <TankPicture shape="horizontal" tank={tank()} />,
    );
    expect(heights(container)).toEqual({ oil: '60%', water: '10%' });
  });

  it("two tanks of one device draw their own heights, not each other's", () => {
    const a = render(
      <TankPicture
        shape="horizontal"
        tank={tank({ index: 1, oilRatioPct: 20 })}
      />,
    );
    const b = render(
      <TankPicture
        shape="horizontal"
        tank={tank({ index: 3, oilRatioPct: 80 })}
      />,
    );
    expect(heights(a.container).oil).toBe('20%');
    expect(heights(b.container).oil).toBe('80%');
  });

  it('draws nothing when the probe is not connected', () => {
    const { container } = render(
      <TankPicture shape="horizontal" tank={tank({ probeConnected: false })} />,
    );
    expect(container.querySelector('[data-fill-state]')).toBeNull();
  });

  it('marks alarm and fault, and stacks water below oil', () => {
    const { container } = render(
      <TankPicture shape="horizontal" tank={tank({ alarmState: 'ALARM' })} />,
    );
    expect(container.querySelector('[data-fill-state="alarm"]')).not.toBeNull();
    expect(
      (container.querySelector('[data-fill-state]') as HTMLElement).style
        .flexDirection,
    ).toBe('column-reverse');
  });

  it('clamps an impossible reading instead of overflowing the tank', () => {
    const { container } = render(
      <TankPicture
        shape="horizontal"
        tank={tank({ oilRatioPct: 300, waterRatioPct: 30 })}
      />,
    );
    expect(heights(container)).toEqual({ oil: '70%', water: '30%' });
  });

  it('puts the tank number in the picture label', () => {
    const { getByRole } = render(
      <TankPicture shape="horizontal" tank={tank({ index: 5 })} />,
    );
    expect(getByRole('img').getAttribute('alt')).toContain('5号罐');
  });
});

it('uses a separate vertical image and fill window, and never guesses an unknown shape', () => {
  const v = render(<TankPicture shape="vertical" tank={tank()} />);
  expect(v.getByRole('img').getAttribute('src')).toBe(
    '/images/tank-vertical.png',
  );
  expect(
    (v.container.querySelector('[data-fill-state]') as HTMLElement).style.left,
  ).toBe('19.9%');
  v.unmount();
  const u = render(<TankPicture tank={tank()} />);
  expect(u.getByRole('img').getAttribute('src')).toBe(
    '/images/tank-unknown.svg',
  );
  expect(u.container.querySelector('[data-fill-state]')).toBeNull();
});
