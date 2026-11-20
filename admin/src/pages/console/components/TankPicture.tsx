import { useIntl } from '@umijs/max';
import type React from 'react';
import { tankFill } from '@/utils/tankFill';
import type { TankShape } from '@/utils/tankShape';

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

// 立罐源图 500×320，可见范围 x=70..266、y=24..317；视口与图片偏移按该范围换算。
// 填充窗口只覆盖两道竖箍之间 x=109..190、y=56..299，不含底座与顶部横边。

/**
 * 罐型来自设备配置镜像，未上报按卧罐；填充只来自这个罐自己的读数。
 * 状态不只靠颜色：报警、探针未连接都在卡片上有文字标签，悬停有「油 x% 水 y%」。
 */
const TankPicture: React.FC<{
  tank: API.Tank;
  width?: number;
  shape?: TankShape;
  /** 设备回报的罐颜色（#RRGGBB）；正常状态按它画油，报警/故障仍用红/灰，空串用默认油色。 */
  oilColor?: string;
}> = ({ tank, width = 96, shape = 'horizontal', oilColor = '' }) => {
  const intl = useIntl();
  const shapeLabel = intl.formatMessage({
    id: `pages.console.tank.shape.${shape}`,
  });
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
      <div
        data-tank-shape={shape}
        style={{
          position: 'absolute',
          // 立罐细长，限在框高内只有卧罐一半面积、显得小：视口放大到框高的 113.6%（上下各超出 6.82%），
          // 宽按素材可见范围 196×293 换算后水平居中；内部百分比相对视口，比例不变。与小程序首页一致。
          inset: shape === 'vertical' ? '-6.82% 22.76%' : 0,
          overflow: 'hidden',
        }}
      >
        <img
          src={
            shape === 'vertical'
              ? '/images/tank-vertical.png'
              : '/images/tank-generic.png'
          }
          alt={intl.formatMessage(
            { id: 'pages.console.tank.shape.picture' },
            { index: tank.index, shape: shapeLabel },
          )}
          style={
            shape === 'vertical'
              ? {
                  position: 'absolute',
                  width: '255.10%',
                  height: '109.22%',
                  left: '-35.71%',
                  top: '-8.19%',
                  maxWidth: 'none',
                }
              : { display: 'block', width: '100%', height: '100%' }
          }
        />
        {f.fillState !== 'none' && (
          <div
            data-fill-state={f.fillState}
            title={`油 ${f.fillOilPct}%　水 ${f.fillWaterPct}%`}
            style={{
              position: 'absolute',
              ...(shape === 'vertical'
                ? {
                    left: '19.9%',
                    width: '41.33%',
                    top: '10.92%',
                    height: '82.94%',
                  }
                : WINDOW),
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
                background:
                  f.fillState === 'normal' && oilColor
                    ? oilColor
                    : OIL_COLOR[f.fillState],
              }}
            />
          </div>
        )}
      </div>
    </div>
  );
};

export default TankPicture;
