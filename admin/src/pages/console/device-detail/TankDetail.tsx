import { useIntl } from '@umijs/max';
import { Col, Descriptions, Row, Space, Tag, Typography } from 'antd';
import type React from 'react';
import { alarmColor, alarmLabel, fmt } from '@/utils/telemetry';
import TankPicture from '../components/TankPicture';
import TankReadout from '../components/TankReadout';

/**
 * 设备详情里一个罐的全部读数：图右侧是设备显示器样式的五行主读数（液位、容积、水高、温度、质量，#21），
 * 报警状态与探针连接状态，下面「更多读数」是水体积、空容、油品占比、水占比。缺失值显示「--」，不显示 0。
 * 图是通用示意加该罐真实液位填充。
 */
const TankDetail: React.FC<{ tank: API.Tank }> = ({ tank }) => {
  const intl = useIntl();
  const t = (id: string) => intl.formatMessage({ id });
  const k = (name: string) => t(`pages.console.detail.snapshot.${name}`);
  const items = [
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
      <Col flex="1 1 280px" style={{ minWidth: 0 }}>
        <TankReadout tank={tank} size="large" />
        <Space wrap style={{ marginTop: 12 }}>
          <Tag color={alarmColor(tank.alarmState)}>
            {k('field.alarm')}：{alarmLabel(tank.alarmState)}
          </Tag>
          <Tag color={tank.probeConnected ? 'success' : 'error'}>
            {k('field.probe')}：
            {k(tank.probeConnected ? 'probe.on' : 'probe.off')}
          </Tag>
        </Space>
      </Col>
      <Col span={24}>
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
      </Col>
    </Row>
  );
};

export default TankDetail;
