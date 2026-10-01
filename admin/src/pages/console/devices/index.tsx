import { getLatestTelemetry, listDevices } from '@/services/devices/api';
import { tankAlarmStateLabel } from '@/services/devices/labels';
import { PageContainer } from '@ant-design/pro-components';
import { history } from '@umijs/max';
import { Alert, Button, Card, Col, Empty, Row, Spin } from 'antd';
import React, { useCallback, useEffect, useState } from 'react';
import TankCard from './components/TankCard';

type DeviceCardState =
  | { kind: 'loading' }
  | { kind: 'no-data' }
  | { kind: 'error'; message: string }
  | { kind: 'tanks'; tanks: DeviceAPI.Tank[] };

// GET /api/devices/:device/telemetry/latest 返回 404 表示"已授权、已配网但从未上报过"，
// 是正常状态而非错误——见 yeweiyi-cloud internal/api/telemetry.go 的注释。
function isNotFoundError(err: unknown): boolean {
  return (err as { response?: { status?: number } })?.response?.status === 404;
}

function errorMessage(err: unknown): string {
  return (err as { message?: string })?.message || '遥测数据加载失败';
}

/**
 * 客户端设备列表——PC 端对应小程序 pages/devices，只返回调用者自己绑定的设备。
 * 先拉设备列表，再对每个设备并发拉最新遥测，按"罐"（而非"设备"）铺开成卡片网格：
 * 一个设备可能报多个罐，每个罐渲染一张 TankCard。
 */
const ConsoleDevices: React.FC = () => {
  const [devices, setDevices] = useState<DeviceAPI.Device[] | null>(null);
  const [deviceStates, setDeviceStates] = useState<Record<string, DeviceCardState>>({});

  const loadTelemetry = useCallback(async (rows: DeviceAPI.Device[]) => {
    setDeviceStates(
      Object.fromEntries(rows.map((d) => [d.device_name, { kind: 'loading' } as const])),
    );
    // Promise.allSettled，而非 Promise.all：任意一个设备的遥测请求失败都不能拖垮其余设备的
    // 卡片渲染（spec: 逐设备隔离）。
    const settled = await Promise.allSettled(rows.map((d) => getLatestTelemetry(d.device_name)));
    const next: Record<string, DeviceCardState> = {};
    settled.forEach((result, i) => {
      const deviceName = rows[i].device_name;
      if (result.status === 'fulfilled') {
        next[deviceName] = { kind: 'tanks', tanks: result.value.tanks ?? [] };
      } else if (isNotFoundError(result.reason)) {
        next[deviceName] = { kind: 'no-data' };
      } else {
        next[deviceName] = { kind: 'error', message: errorMessage(result.reason) };
      }
    });
    setDeviceStates(next);
  }, []);

  const load = useCallback(async () => {
    setDevices(null);
    const res = await listDevices();
    const rows = res.rows ?? [];
    setDevices(rows);
    if (rows.length > 0) {
      await loadTelemetry(rows);
    }
  }, [loadTelemetry]);

  useEffect(() => {
    load();
  }, [load]);

  const retryDevice = useCallback(async (deviceName: string) => {
    setDeviceStates((prev) => ({ ...prev, [deviceName]: { kind: 'loading' } }));
    try {
      const res = await getLatestTelemetry(deviceName);
      setDeviceStates((prev) => ({
        ...prev,
        [deviceName]: { kind: 'tanks', tanks: res.tanks ?? [] },
      }));
    } catch (err) {
      setDeviceStates((prev) => ({
        ...prev,
        [deviceName]: isNotFoundError(err)
          ? { kind: 'no-data' }
          : { kind: 'error', message: errorMessage(err) },
      }));
    }
  }, []);

  if (devices === null) {
    return (
      <PageContainer title="设备列表">
        <div style={{ display: 'flex', justifyContent: 'center', padding: '80px 0' }}>
          <Spin size="large" />
        </div>
      </PageContainer>
    );
  }

  if (devices.length === 0) {
    return (
      <PageContainer title="设备列表">
        <Empty description="当前账号未绑定任何设备" style={{ padding: '80px 0' }} />
      </PageContainer>
    );
  }

  return (
    <PageContainer title="设备列表">
      <Row gutter={[16, 16]}>
        {devices.map((device) => {
          const state: DeviceCardState = deviceStates[device.device_name] ?? { kind: 'loading' };

          if (state.kind === 'loading') {
            return (
              <Col key={device.device_name} xs={24} sm={12} md={8} lg={6}>
                <Card loading style={{ minHeight: 160 }} />
              </Col>
            );
          }

          if (state.kind === 'no-data') {
            return (
              <Col key={device.device_name} xs={24} sm={12} md={8} lg={6}>
                <Card size="small" title={device.device_name} style={{ minHeight: 160 }}>
                  <Empty
                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                    description="该设备尚未上报数据"
                  />
                </Card>
              </Col>
            );
          }

          if (state.kind === 'error') {
            return (
              <Col key={device.device_name} xs={24} sm={12} md={8} lg={6}>
                <Card size="small" title={device.device_name} style={{ minHeight: 160 }}>
                  <Alert
                    type="error"
                    message={state.message}
                    showIcon
                    style={{ marginBottom: 12 }}
                  />
                  <Button size="small" onClick={() => retryDevice(device.device_name)}>
                    重试
                  </Button>
                </Card>
              </Col>
            );
          }

          return state.tanks.map((tank) => {
            const abnormal = !!tank.alarmState && tank.alarmState !== 'NORMAL';
            return (
              <Col key={`${device.device_name}-${tank.index}`} xs={24} sm={12} md={8} lg={6}>
                <TankCard
                  tankNo={`${tank.index}号`}
                  medium={
                    abnormal
                      ? `${tank.oilType} · ${tankAlarmStateLabel(tank.alarmState)}`
                      : tank.oilType
                  }
                  offline={!tank.probeConnected || tank.alarmState === 'FAULT'}
                  metrics={[
                    { label: '液位', value: tank.oilLevelMm, unit: 'mm' },
                    { label: '容积', value: tank.oilVolumeL, unit: 'L' },
                    { label: '水位', value: tank.waterLevelMm, unit: 'mm' },
                    { label: '温度', value: tank.tempC, unit: '℃' },
                    { label: '质量', value: tank.oilWeightKg, unit: 'kg' },
                  ]}
                  onClick={() => history.push(`/console/devices/${device.device_name}`)}
                />
              </Col>
            );
          });
        })}
      </Row>
    </PageContainer>
  );
};

export default ConsoleDevices;
