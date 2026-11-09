import { useIntl } from '@umijs/max';
import { theme } from 'antd';
import type React from 'react';
import { fmt } from '@/utils/telemetry';

/** 标题条的深蓝底：对照中成设备显示器照片（#21），白字在这个底色上对比度足够（> 7:1）。 */
const HEADER_BG = '#1d3d8f';

/** 五行主读数，顺序固定：液位 → 容积 → 水高 → 温度 → 质量（#21）。标签沿用详情页的字段文案。 */
const ROWS = [
  ['oilLevel', 'oilLevelMm', 'mm'],
  ['oilVolume', 'oilVolumeL', 'L'],
  ['waterLevel', 'waterLevelMm', 'mm'],
  ['temp', 'tempC', 'C'],
  ['oilWeight', 'oilWeightKg', 'kg'],
] as const;

/**
 * 油罐图右侧的「显示器样式」读数（#21）：深蓝底白字的「罐号 + 介质」标题条，下面五行「标签 数值 单位」，
 * 三列纵向对齐。数值是该罐真实遥测，保留一位小数，0 显示 0.0，缺失显示「--」（不当成 0）。
 * 单位按语言：中文是毫米/升/℃/千克（照片上的写法），其他语言沿用 mm/L/℃/kg。
 * `extra` 放在标题条右侧（报警、探针状态标签），不能被样式遮住。
 */
const TankReadout: React.FC<{
  tank: API.Tank;
  extra?: React.ReactNode;
  size?: 'default' | 'large';
}> = ({ tank, extra, size = 'default' }) => {
  const intl = useIntl();
  const { token } = theme.useToken();
  const t = (id: string, values?: Record<string, string | number>) =>
    intl.formatMessage({ id }, values);
  const large = size === 'large';
  const medium = tank.oilType?.trim() ? tank.oilType : '--';
  return (
    // 读数区限宽：照片上标签、数值、单位挨得较紧，拉满一整行会让三列离得太远
    <div style={{ maxWidth: large ? 380 : 300 }}>
      <div
        style={{
          background: HEADER_BG,
          color: '#fff',
          padding: '4px 12px',
          borderRadius: 2,
          fontSize: large ? 18 : 16,
          fontWeight: 600,
          display: 'flex',
          flexWrap: 'wrap',
          alignItems: 'center',
          gap: 8,
          marginBottom: 8,
        }}
      >
        <h4
          style={{
            margin: 0,
            color: 'inherit',
            font: 'inherit',
            overflowWrap: 'anywhere',
            minWidth: 0,
          }}
        >
          {t('pages.console.tank.title', { index: tank.index, medium })}
        </h4>
        {extra}
      </div>
      {/* 真表格：标签、数值、单位三列天然纵向对齐；数值列吃掉剩余宽度并右对齐，大数、负温度不挤掉单位 */}
      <table
        aria-label={t('pages.console.tank.readings')}
        style={{
          width: '100%',
          borderCollapse: 'collapse',
          color: token.colorText,
          fontSize: large ? 20 : 16,
          lineHeight: large ? 1.7 : 1.6,
        }}
      >
        <tbody>
          {ROWS.map(([label, field, unit]) => (
            <tr key={field}>
              <th
                scope="row"
                style={{
                  textAlign: 'left',
                  fontWeight: 400,
                  padding: 0,
                  whiteSpace: 'nowrap',
                  width: '1%',
                }}
              >
                {t(`pages.console.detail.snapshot.field.${label}`)}
              </th>
              <td
                style={{
                  whiteSpace: 'nowrap',
                  textAlign: 'right',
                  fontWeight: 600,
                  fontVariantNumeric: 'tabular-nums',
                  padding: `0 ${large ? 16 : 12}px`,
                  overflowWrap: 'anywhere',
                }}
              >
                {fmt(tank[field])}
              </td>
              <td
                style={{
                  color: token.colorTextSecondary,
                  padding: 0,
                  whiteSpace: 'nowrap',
                  width: '1%',
                }}
              >
                {t(`pages.console.tank.unit.${unit}`)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

export default TankReadout;
