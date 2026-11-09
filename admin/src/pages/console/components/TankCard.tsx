import { ProCard } from '@ant-design/pro-components';
import { Col, Row, Tag } from 'antd';
import type React from 'react';
import { alarmColor, alarmLabel } from '@/utils/telemetry';
import TankPicture from './TankPicture';
import TankReadout from './TankReadout';

/**
 * 一个油罐的最新遥测卡片：左侧通用罐示意图（真实液位填充），右侧按设备显示器样式排的读数（#21）。
 * 设备总览用，点击进入该罐的详情。报警、探针未连接的标签在标题条里，不被样式遮住。
 */
export const TankCard: React.FC<{ tank: API.Tank; onClick: () => void }> = ({
  tank,
  onClick,
}) => (
  <ProCard hoverable onClick={onClick} styles={{ body: { padding: 8 } }}>
    <Row gutter={[8, 8]} align="middle" wrap>
      <Col flex="0 0 72px">
        <TankPicture tank={tank} width={72} />
      </Col>
      <Col flex="1 1 170px" style={{ minWidth: 0 }}>
        <TankReadout
          tank={tank}
          extra={
            <>
              {tank.alarmState !== 'NORMAL' && (
                <Tag
                  color={alarmColor(tank.alarmState)}
                  style={{ marginInlineEnd: 0 }}
                >
                  {alarmLabel(tank.alarmState)}
                </Tag>
              )}
              {!tank.probeConnected && (
                <Tag color="error" style={{ marginInlineEnd: 0 }}>
                  探针未连接
                </Tag>
              )}
            </>
          }
        />
      </Col>
    </Row>
  </ProCard>
);

export default TankCard;
