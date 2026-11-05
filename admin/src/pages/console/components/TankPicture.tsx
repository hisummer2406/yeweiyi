import type React from 'react';
import { tankFill } from '@/utils/tankFill';

// 罐图里两道箍之间的液位窗口，占图片的百分比。对着 public/images/tank-generic.png（安卓 max_oilcan.png 裁剪后
// 240×172）逐像素量的：窗口左右取两道箍的内沿，上沿是罐身顶（不盖住罐口颈），下沿是罐身底（不盖住底座）。
// 换图要重新量。
const WINDOW = {
  left: '23.71%',
  width: '19.48%',
  top: '13.76%',
  height: '79.59%',
};

const OIL_COLOR = {
  normal: '#faad14',
  alarm: '#ff4d4f',
  fault: '#8c8c8c',
} as const;
const WATER_COLOR = '#1677ff';

/**
 * 罐示意图 + 该罐真实的液位填充（oilRatioPct / waterRatioPct，水在下、油在上）。图本身是通用卧罐，不代表真实罐型
 * （云端没有罐型字段，cloud#45）；填充只来自这个罐自己的读数：探针未连接不画，占比异常夹到 0..100。
 * 状态不只靠颜色：报警、探针未连接都在卡片上有文字标签，悬停有「油 x% 水 y%」。
 */
const TankPicture: React.FC<{ tank: API.Tank; width?: number }> = ({
  tank,
  width = 96,
}) => {
  const f = tankFill(tank);
  return (
    <div
      style={{
        position: 'relative',
        width,
        maxWidth: '100%',
        aspectRatio: '240 / 172',
      }}
    >
      <img
        src="/images/tank-generic.png"
        alt={`${tank.index}号罐示意图（通用，非实际罐型）`}
        style={{ display: 'block', width: '100%', height: '100%' }}
      />
      {f.fillState !== 'none' && (
        <div
          data-fill-state={f.fillState}
          title={`油 ${f.fillOilPct}%　水 ${f.fillWaterPct}%`}
          style={{
            position: 'absolute',
            ...WINDOW,
            display: 'flex',
            flexDirection: 'column-reverse',
            overflow: 'hidden',
            // 乘色叠加：罐身的高光与阴影透出来，填充看起来在罐里
            mixBlendMode: 'multiply',
            opacity: 0.85,
          }}
        >
          <div
            data-fill="water"
            style={{ height: `${f.fillWaterPct}%`, background: WATER_COLOR }}
          />
          <div
            data-fill="oil"
            style={{
              height: `${f.fillOilPct}%`,
              background: OIL_COLOR[f.fillState],
            }}
          />
        </div>
      )}
    </div>
  );
};

export default TankPicture;
