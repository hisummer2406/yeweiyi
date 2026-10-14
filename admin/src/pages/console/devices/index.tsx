import { ReloadOutlined } from '@ant-design/icons';
import { PageContainer, ProCard } from '@ant-design/pro-components';
import { history } from '@umijs/max';
import {
  Alert,
  Badge,
  Button,
  Col,
  Empty,
  Row,
  Skeleton,
  Space,
  Typography,
} from 'antd';
import type React from 'react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { listDevices } from '@/services/ant-design-pro/devices';
import { getLatestTelemetry } from '@/services/ant-design-pro/telemetry';
import { sectionByStation } from '@/utils/stationFilter';
import { fmtTime } from '@/utils/telemetry';
import TankCard from '../components/TankCard';
import {
  StationFilter,
  useStationFilter,
} from '../components/useStationFilter';

/** 自动刷新间隔：遥测是快照而非实时推送，30 秒足够。 */
const REFRESH_MS = 30_000;

/** 一台设备在总览里的状态。每台独立，一台失败不影响其它。 */
type Group = {
  device: API.DeviceRow;
  state: 'loading' | 'ok' | 'never' | 'error';
  telemetry?: API.LatestTelemetry;
  error?: string;
};

/**
 * 设备总览——PC 端对应小程序首页（pages/devices）：
 * GET /api/devices 取调用者可见的设备，再对每台并行取最新遥测（后端没有批量接口）。
 */
const ConsoleDevices: React.FC = () => {
  const [groups, setGroups] = useState<Group[] | null>(null);
  const [listError, setListError] = useState('');
  const [refreshing, setRefreshing] = useState(false);
  // 每次刷新自增；旧一轮的响应晚到时丢弃，避免覆盖新数据。
  const seq = useRef(0);

  const patch = useCallback(
    (name: string, mySeq: number, p: Partial<Group>) => {
      if (mySeq !== seq.current) return;
      setGroups((prev) =>
        prev
          ? prev.map((g) =>
              g.device.device_name === name ? { ...g, ...p } : g,
            )
          : prev,
      );
    },
    [],
  );

  const loadDevice = useCallback(
    async (name: string, mySeq: number) => {
      try {
        const t = await getLatestTelemetry(name);
        patch(
          name,
          mySeq,
          t
            ? { state: 'ok', telemetry: t, error: undefined }
            : { state: 'never', telemetry: undefined, error: undefined },
        );
      } catch (e: any) {
        patch(name, mySeq, {
          state: 'error',
          error: e?.response?.data?.error || e?.message || '加载失败',
        });
      }
    },
    [patch],
  );

  const load = useCallback(async () => {
    const mySeq = ++seq.current;
    setRefreshing(true);
    try {
      const rows = await listDevices();
      if (mySeq !== seq.current) return;
      setListError('');
      // 保留已有遥测，刷新时不闪回骨架屏。
      setGroups((prev) =>
        rows.map((device) => {
          const old = prev?.find(
            (g) => g.device.device_name === device.device_name,
          );
          return old
            ? { ...old, device }
            : { device, state: 'loading' as const };
        }),
      );
      await Promise.all(rows.map((d) => loadDevice(d.device_name, mySeq)));
    } catch (e: any) {
      if (mySeq !== seq.current) return;
      setListError(
        e?.response?.data?.error || e?.message || '加载设备列表失败',
      );
      setGroups((prev) => prev ?? []);
    } finally {
      if (mySeq === seq.current) setRefreshing(false);
    }
  }, [loadDevice]);

  useEffect(() => {
    load();
    const timer = setInterval(load, REFRESH_MS);
    return () => {
      clearInterval(timer);
      seq.current++; // 卸载后丢弃在途响应
    };
  }, [load]);

  const sf = useStationFilter(groups?.map((g) => g.device));
  // 筛选只改变渲染：已取到的遥测直接按筛选重新显示，不触发额外请求，轮询逻辑不变
  const shown = (groups ?? []).filter((g) => sf.matches(g.device));
  const sectioned = sf.show && sf.value === 'all';

  // 带上油站：从油站分段进入的详情页会在换机后自动切到该油站的新设备（契约 §8.1）
  const openDetail = (name: string, stationId?: number | null) =>
    history.push(
      `/console/devices/${encodeURIComponent(name)}${
        typeof stationId === 'number' ? `?station=${stationId}` : ''
      }`,
    );

  const renderGroup = (g: Group) => (
    <ProCard
      key={g.device.device_name}
      headerBordered
      title={
        <Space>
          <Typography.Text strong>{g.device.device_name}</Typography.Text>
          <Badge
            status={g.device.is_online ? 'success' : 'default'}
            text={g.device.is_online ? '在线' : '离线'}
          />
        </Space>
      }
      extra={
        <Typography.Text type="secondary">
          {g.telemetry
            ? `上报时间 ${fmtTime(g.telemetry.ts)}`
            : g.device.is_online
              ? ''
              : `最后在线 ${fmtTime(g.device.last_online_at)}`}
        </Typography.Text>
      }
    >
      {g.state === 'loading' && <Skeleton active />}
      {g.state === 'never' && <Empty description="该设备还没有上报过数据" />}
      {g.state === 'error' && (
        <Alert
          type="error"
          showIcon
          message={g.error}
          action={
            <Button
              size="small"
              onClick={() => {
                const s = seq.current;
                patch(g.device.device_name, s, { state: 'loading' });
                loadDevice(g.device.device_name, s);
              }}
            >
              重试
            </Button>
          }
        />
      )}
      {g.state === 'ok' && g.telemetry && (
        <Row gutter={[16, 16]}>
          {g.telemetry.tanks.map((t) => (
            <Col key={t.index} xs={24} md={12} xl={8}>
              <TankCard
                tank={t}
                onClick={() =>
                  openDetail(g.device.device_name, g.device.station_id)
                }
              />
            </Col>
          ))}
        </Row>
      )}
    </ProCard>
  );

  let body: React.ReactNode;
  if (groups === null) {
    body = <Skeleton active />;
  } else if (groups.length === 0) {
    body = listError ? (
      <Alert
        type="error"
        showIcon
        message={listError}
        action={<Button onClick={load}>重试</Button>}
      />
    ) : (
      <Empty description="还没有可查看的设备" />
    );
  } else {
    body = (
      <Space direction="vertical" size="large" style={{ width: '100%' }}>
        {listError && <Alert type="warning" showIcon message={listError} />}
        {sf.show && shown.length === 0 && (
          <Empty description="该范围内没有设备" />
        )}
        {sectioned
          ? sectionByStation(
              shown.map((g) => ({ ...g, station_id: g.device.station_id })),
              sf.visibleIds,
            ).map((sec) => (
              <section key={String(sec.key)}>
                <Typography.Title level={5}>
                  {sec.key === 'none' ? '未分组' : sf.labelOf(sec.key)}
                </Typography.Title>
                <Space
                  direction="vertical"
                  size="large"
                  style={{ width: '100%' }}
                >
                  {sec.items.map(renderGroup)}
                </Space>
              </section>
            ))
          : shown.map(renderGroup)}
      </Space>
    );
  }

  return (
    <PageContainer
      title="设备总览"
      extra={[
        <Button
          key="refresh"
          icon={<ReloadOutlined />}
          loading={refreshing}
          onClick={load}
        >
          刷新
        </Button>,
      ]}
    >
      {sf.show && (
        <Space style={{ marginBottom: 16 }}>
          <span>油站</span>
          <StationFilter
            value={sf.value}
            onChange={sf.setValue}
            stations={sf.stations}
            labelOf={sf.labelOf}
          />
        </Space>
      )}
      {body}
    </PageContainer>
  );
};

export default ConsoleDevices;
