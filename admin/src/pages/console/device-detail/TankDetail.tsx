import { useIntl } from '@umijs/max';
import { Descriptions, Tag, Typography, theme } from 'antd';
import type React from 'react';
import { tankShapeOf } from '@/utils/tankShape';
import { alarmColor, alarmLabel, fmt } from '@/utils/telemetry';
import styles from '../components/TankDisplay.module.less';
import TankPicture from '../components/TankPicture';
import TankReadout from '../components/TankReadout';
import TankShapeLabel, {
  type TankShapeInfo,
} from '../components/TankShapeLabel';

/**
 * 设备详情里一个罐的全部读数：图右侧是设备显示器样式的五行主读数（液位、容积、水高、温度、质量，#21），
 * 报警状态与探针连接状态，下面「更多读数」是水体积、空容、油品占比、水占比。缺失值显示「--」，不显示 0。
 * 图按设备回报罐型显示，加该罐真实液位填充。
 */
const TankDetail: React.FC<{ tank: API.Tank; shapeInfo?: TankShapeInfo }> = ({
  tank,
  shapeInfo = tankShapeOf(undefined, tank.index),
}) => {
  const intl = useIntl();
  const { token } = theme.useToken();
  const t = (id: string) => intl.formatMessage({ id });
  const k = (name: string) => t(`pages.console.detail.snapshot.${name}`);
  const items = [
    ['waterVolume', fmt(tank.waterVolumeL), 'L'],
    ['nullVolume', fmt(tank.nullVolumeL), 'L'],
    ['oilRatio', fmt(tank.oilRatioPct), '%'],
    ['waterRatio', fmt(tank.waterRatioPct), '%'],
  ] as const;
  return (
    <div className={styles.detail}>
      <div
        className={styles.display}
        style={{ background: token.colorFillAlter }}
      >
        <div className={styles.picture}>
          <TankPicture tank={tank} width={360} shape={shapeInfo.shape} />
        </div>
        <TankReadout
          tank={tank}
          size="large"
          extra={
            <>
              <TankShapeLabel info={shapeInfo} />
              <Tag color={alarmColor(tank.alarmState)}>
                {k('field.alarm')}：{alarmLabel(tank.alarmState)}
              </Tag>
              <Tag color={tank.probeConnected ? 'success' : 'error'}>
                {k('field.probe')}：
                {k(tank.probeConnected ? 'probe.on' : 'probe.off')}
              </Tag>
            </>
          }
        />
      </div>
      <div style={{ marginTop: 24 }}>
        <Typography.Text type="secondary">{k('more')}</Typography.Text>
        <Descriptions
          style={{ marginTop: 8 }}
          bordered
          size="small"
          column={{ xs: 1, sm: 2, lg: 3 }}
          items={items.map(([name, value, unit]) => ({
            key: name,
            label: k(`field.${name}`),
            children: value === '--' ? '--' : `${value} ${unit}`,
          }))}
        />
      </div>
    </div>
  );
};

export default TankDetail;
