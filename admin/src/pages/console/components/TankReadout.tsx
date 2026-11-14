import { useIntl } from '@umijs/max';
import { theme } from 'antd';
import type React from 'react';
import { fmt } from '@/utils/telemetry';
import styles from './TankDisplay.module.less';

/** 五行主读数，顺序与设备显示器一致。 */
const ROWS = [
  ['oilLevel', 'oilLevelMm', 'mm'],
  ['oilVolume', 'oilVolumeL', 'L'],
  ['waterLevel', 'waterLevelMm', 'mm'],
  ['temp', 'tempC', 'C'],
  ['oilWeight', 'oilWeightKg', 'kg'],
] as const;

/** 罐号与介质标题条，读数按标签/数值/单位对齐；状态置于表格下方，不挤压标题。 */
const TankReadout: React.FC<{
  tank: API.Tank;
  extra?: React.ReactNode;
  size?: 'default' | 'large';
}> = ({ tank, extra, size = 'default' }) => {
  const intl = useIntl();
  const { token } = theme.useToken();
  const t = (id: string, values?: Record<string, string | number>) =>
    intl.formatMessage({ id }, values);
  const medium = tank.oilType?.trim() ? tank.oilType : '--';
  return (
    <div
      className={`${styles.readout} ${size === 'large' ? styles.large : ''}`}
    >
      <div className={styles.header}>
        <h4 className={styles.title}>
          {t('pages.console.tank.title', { index: tank.index, medium })}
        </h4>
      </div>
      <table
        className={styles.readings}
        aria-label={t('pages.console.tank.readings')}
        style={{ color: token.colorText }}
      >
        <tbody>
          {ROWS.map(([label, field, unit]) => (
            <tr key={field}>
              <th scope="row" style={{ color: token.colorTextSecondary }}>
                {t(`pages.console.detail.snapshot.field.${label}`)}
              </th>
              <td>{fmt(tank[field])}</td>
              <td style={{ color: token.colorTextSecondary }}>
                {t(`pages.console.tank.unit.${unit}`)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className={styles.status}>{extra}</div>
    </div>
  );
};

export default TankReadout;
