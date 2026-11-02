import { ProCard } from '@ant-design/pro-components';
import { Col, Row, Space, Statistic, Tag } from 'antd';
import type React from 'react';
import { alarmColor, alarmLabel, fmt } from '@/utils/telemetry';

/** 一个油罐的最新遥测卡片（带通用罐示意图）：设备总览与设备详情的实时数据页签共用。 */
export const TankCard: React.FC<{ tank: API.Tank; onClick: () => void }> = ({
  tank,
  onClick,
}) => (
  <ProCard
    hoverable
    onClick={onClick}
    title={
      <Space>
        <span>
          {tank.index}号 {tank.oilType || '--'}
        </span>
        {tank.alarmState !== 'NORMAL' && (
          <Tag color={alarmColor(tank.alarmState)}>
            {alarmLabel(tank.alarmState)}
          </Tag>
        )}
        {!tank.probeConnected && <Tag color="error">探针未连接</Tag>}
      </Space>
    }
  >
    <Row gutter={[16, 16]} align="middle" wrap>
      <Col flex="0 0 96px">
        {/* 通用示意图（取自安卓端卧罐图），不是该罐的真实罐型：云端还没有罐型字段（cloud#45）。
            不随液位、状态变色，状态靠标题里的文字标签。 */}
        <img
          src="/images/tank-generic.png"
          alt={`${tank.index}号罐示意图（通用，非实际罐型）`}
          width={96}
          style={{ display: 'block', maxWidth: '100%', height: 'auto' }}
        />
      </Col>
      <Col flex="1 1 240px">
        <Row gutter={[16, 16]}>
          <Col span={12}>
            <Statistic title="液位" value={fmt(tank.oilLevelMm)} suffix="mm" />
          </Col>
          <Col span={12}>
            <Statistic title="容积" value={fmt(tank.oilVolumeL)} suffix="L" />
          </Col>
          <Col span={12}>
            <Statistic
              title="水高"
              value={fmt(tank.waterLevelMm)}
              suffix="mm"
            />
          </Col>
          <Col span={12}>
            <Statistic title="温度" value={fmt(tank.tempC)} suffix="℃" />
          </Col>
          <Col span={12}>
            <Statistic title="质量" value={fmt(tank.oilWeightKg)} suffix="kg" />
          </Col>
        </Row>
      </Col>
    </Row>
  </ProCard>
);

export default TankCard;
