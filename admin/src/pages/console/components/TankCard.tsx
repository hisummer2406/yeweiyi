import { ProCard } from '@ant-design/pro-components';
import { Tag, theme } from 'antd';
import type React from 'react';
import { tankShapeOf } from '@/utils/tankShape';
import { alarmColor, alarmLabel } from '@/utils/telemetry';
import styles from './TankDisplay.module.less';
import TankPicture from './TankPicture';
import TankReadout from './TankReadout';
import type { TankShapeInfo } from './TankShapeLabel';

/** 总览罐卡：罐图与五行读数按卡片宽度成比例排布，状态文字独立展示。 */
export const TankCard: React.FC<{
  tank: API.Tank;
  onClick: () => void;
  shapeInfo?: TankShapeInfo;
}> = ({ tank, onClick, shapeInfo = tankShapeOf(undefined, tank.index) }) => {
  const { token } = theme.useToken();
  return (
    <ProCard
      className={styles.card}
      hoverable
      onClick={onClick}
      styles={{ body: { padding: 0 } }}
    >
      <div
        className={styles.display}
        style={{ background: token.colorFillAlter }}
      >
        <div className={styles.picture}>
          <TankPicture tank={tank} width={240} shape={shapeInfo.shape} />
        </div>
        <TankReadout
          tank={tank}
          extra={
            <>
              {tank.alarmState !== 'NORMAL' && (
                <Tag color={alarmColor(tank.alarmState)}>
                  {alarmLabel(tank.alarmState)}
                </Tag>
              )}
              {!tank.probeConnected && <Tag color="error">探针未连接</Tag>}
            </>
          }
        />
      </div>
    </ProCard>
  );
};

export default TankCard;
