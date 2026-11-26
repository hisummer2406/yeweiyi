// 罐示意图上的液位填充（与小程序 utils/tankFill.ts 同一规则）。纯函数。
//
// 只用该罐自己的占比（oilRatioPct / waterRatioPct，占罐容积的百分比）：水在最下面、油在水上面，两者不重叠，
// 合起来不超过整罐。读数异常不照单全收：不是数字或超出 0..100 的占比被夹到范围内（NaN 当 0），不画破图。
// 探针断线（设备按自己的断线阈值才报，#75）保留上次液位，画成灰色 stale；从未采到读数（hasReading=false）不画。

export type TankFill = {
  fillOilPct: number;
  fillWaterPct: number;
  fillState: 'normal' | 'alarm' | 'fault' | 'stale' | 'none';
};

export function tankFill(
  t: Pick<
    API.Tank,
    'oilRatioPct' | 'waterRatioPct' | 'alarmState' | 'probeConnected' | 'hasReading'
  >,
): TankFill {
  const clamp = (n: number) =>
    Number.isFinite(n) ? Math.min(100, Math.max(0, n)) : 0;
  if (t.hasReading === false)
    return { fillOilPct: 0, fillWaterPct: 0, fillState: 'none' };
  const water = clamp(t.waterRatioPct);
  const oil = Math.min(clamp(t.oilRatioPct), 100 - water);
  const fillState = !t.probeConnected
    ? 'stale'
    : t.alarmState === 'ALARM'
      ? 'alarm'
      : t.alarmState === 'FAULT'
        ? 'fault'
        : 'normal';
  return {
    fillOilPct: Math.round(oil * 10) / 10,
    fillWaterPct: Math.round(water * 10) / 10,
    fillState,
  };
}
