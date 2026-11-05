import { useIntl } from '@umijs/max';
import { Col, Descriptions, Row, Space, Tag } from 'antd';
import type React from 'react';
import { alarmColor, alarmLabel, fmt } from '@/utils/telemetry';
import TankPicture from '../components/TankPicture';

/**
 * 设备详情里一个罐的全部读数（对应小程序详情页的数据网格）：液位、容积、质量、温度、水高、水体积、空容、
 * 油品占比、水占比，以及报警状态与探针连接状态。缺失值显示「--」，不显示 0。图是通用示意加该罐真实液位填充。
 */
const TankDetail: React.FC<{ tank: API.Tank }> = ({ tank }) => {
  const intl = useIntl();
  const t = (id: string) => intl.formatMessage({ id });
  const k = (name: string) => t(`pages.console.detail.snapshot.${name}`);
  const items = [
    ['oilLevel', fmt(tank.oilLevelMm), 'mm'],
    ['oilVolume', fmt(tank.oilVolumeL), 'L'],
    ['oilWeight', fmt(tank.oilWeightKg), 'kg'],
    ['temp', fmt(tank.tempC), '℃'],
    ['waterLevel', fmt(tank.waterLevelMm), 'mm'],
    ['waterVolume', fmt(tank.waterVolumeL), 'L'],
    ['nullVolume', fmt(tank.nullVolumeL), 'L'],
    ['oilRatio', fmt(tank.oilRatioPct), '%'],
    ['waterRatio', fmt(tank.waterRatioPct), '%'],
  ] as const;
  return (
    <Row gutter={[24, 16]} align="middle" wrap>
      <Col flex="0 0 200px">
        <TankPicture tank={tank} width={200} />
      </Col>
      <Col flex="1 1 320px">
        <Space wrap style={{ marginBottom: 12 }}>
          <Tag color={alarmColor(tank.alarmState)}>
            {k('field.alarm')}：{alarmLabel(tank.alarmState)}
          </Tag>
          <Tag color={tank.probeConnected ? 'success' : 'error'}>
            {k('field.probe')}：
            {k(tank.probeConnected ? 'probe.on' : 'probe.off')}
          </Tag>
        </Space>
        <Descriptions
          bordered
          size="small"
          column={{ xs: 1, sm: 2, lg: 3 }}
          items={items.map(([name, value, unit]) => ({
            key: name,
            label: k(`field.${name}`),
            children: value === '--' ? '--' : `${value} ${unit}`,
          }))}
        />
      </Col>
    </Row>
  );
};

export default TankDetail;
