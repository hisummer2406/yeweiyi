import { ReloadOutlined } from '@ant-design/icons';
import { PageContainer, ProCard } from '@ant-design/pro-components';
import { history } from '@umijs/max';
import {
  Alert,
  Badge,
  Button,
  Col,
  Empty,
  Pagination,
  Row,
  Skeleton,
  Space,
  Typography,
} from 'antd';
import type React from 'react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { listDevices } from '@/services/ant-design-pro/devices';
import { getLatestTelemetry } from '@/services/ant-design-pro/telemetry';
import {
  mapLimit,
  OVERVIEW_PAGE_SIZE,
  packPages,
  TELEMETRY_CONCURRENCY,
} from '@/utils/overviewPaging';
import { fmtTime } from '@/utils/telemetry';
import TankCard from '../components/TankCard';
import {
  CustomerFilter,
  StationFilter,
  useStationFilter,
} from '../components/useStationFilter';

/** 自动刷新间隔：遥测是快照而非实时推送，30 秒足够。 */
const REFRESH_MS = 30_000;

/** 一台设备在总览里的状态。每台独立，一台失败不影响其它。 */
type Group = {
  device: API.DeviceRow;
  /** idle：还没轮到取遥测（不在当前页）；翻到该页时才请求 */
  state: 'idle' | 'loading' | 'ok' | 'never' | 'error';
  telemetry?: API.LatestTelemetry;
  error?: string;
};

/**
 * 设备总览——PC 端对应小程序首页（pages/devices）：
 * GET /api/devices 取调用者可见的设备，再对**当前页**的每台取最新遥测（后端没有批量接口）。
 * 分页只在前端：设备行很轻，列表一次取全；遥测按页、限并发请求，标签页不可见时不轮询。
 */
const ConsoleDevices: React.FC = () => {
  const [groups, setGroups] = useState<Group[] | null>(null);
  const [listError, setListError] = useState('');
  const [refreshing, setRefreshing] = useState(false);
  const [page, setPage] = useState(1);
  // 当前页上的设备编号：轮询只刷新它们
  const visibleRef = useRef<string[]>([]);
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
          return old ? { ...old, device } : { device, state: 'idle' as const };
        }),
      );
      // 只刷新当前页仍存在的设备；新设备与翻页后的设备由下面的 effect 按需取
      const existing = new Set(rows.map((d) => d.device_name));
      await mapLimit(
        visibleRef.current.filter((n) => existing.has(n)),
        TELEMETRY_CONCURRENCY,
        (n) => loadDevice(n, mySeq),
      );
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
    // 标签页不可见时不轮询；回到前台立即补一次
    const timer = setInterval(() => {
      if (!document.hidden) load();
    }, REFRESH_MS);
    const onVisible = () => {
      if (!document.hidden) load();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisible);
      seq.current++; // 卸载后丢弃在途响应
    };
  }, [load]);

  const sf = useStationFilter(groups?.map((g) => g.device));
  // 筛选只改变渲染：已取到的遥测直接按筛选重新显示，不触发额外请求，轮询逻辑不变
  const shown = (groups ?? []).filter((g) => sf.matches(g.device));
  // 一油站一台大屏：每张卡片就是一个油站，不再按油站分段；按油站顺序排列，未分组的在最后
  const order = (g: Group) => {
    const i = sf.visibleIds.indexOf(g.device.station_id as number);
    return i < 0 ? Number.MAX_SAFE_INTEGER : i;
  };
  const ordered = sf.show
    ? [...shown].sort((x, y) => order(x) - order(y))
    : shown;
  const pages: Group[][] = packPages(ordered, () => 1, OVERVIEW_PAGE_SIZE);
  const pageCount = pages.length;
  const current = Math.min(page, Math.max(pageCount, 1));
  const pageGroups: Group[] = pages[current - 1] ?? [];

  // 换筛选回到第一页；设备减少导致页码越界由 current 兜底
  useEffect(() => {
    setPage(1);
  }, [sf.value]);

  visibleRef.current = pageGroups.map((g) => g.device.device_name);
  const idleKey = pageGroups
    .filter((g) => g.state === 'idle')
    .map((g) => g.device.device_name)
    .join('\n');

  // 翻到的页上还没取过遥测的设备：现在才取（已取过的沿用缓存，下一轮轮询刷新）
  useEffect(() => {
    if (!idleKey) return;
    const names = idleKey.split('\n');
    const mySeq = seq.current;
    setGroups((prev) =>
      prev
        ? prev.map((g) =>
            names.includes(g.device.device_name) && g.state === 'idle'
              ? { ...g, state: 'loading' }
              : g,
          )
        : prev,
    );
    mapLimit(names, TELEMETRY_CONCURRENCY, (n) => loadDevice(n, mySeq));
  }, [idleKey, loadDevice]);

  // 带上油站：从油站分段进入的详情页会在换机后自动切到该油站的新设备（契约 §8.1）
  // tank 是点的那个罐的协议罐号：详情只显示这个罐（同小程序「点谁是谁」）
  const openDetail = (name: string, tank: number, stationId?: number | null) =>
    history.push(
      `/console/devices/${encodeURIComponent(name)}?tank=${tank}${
        typeof stationId === 'number' ? `&station=${stationId}` : ''
      }`,
    );

  const renderGroup = (g: Group) => (
    <ProCard
      key={g.device.device_name}
      headerBordered
      title={
        <Space>
          {typeof g.device.station_id === 'number' ? (
            <>
              <Typography.Text strong>
                {sf.labelOf(g.device.station_id)}
              </Typography.Text>
              <Typography.Text type="secondary">
                {g.device.device_name}
              </Typography.Text>
            </>
          ) : (
            <Typography.Text strong>{g.device.device_name}</Typography.Text>
          )}
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
      {(g.state === 'loading' || g.state === 'idle') && <Skeleton active />}
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
                  openDetail(g.device.device_name, t.index, g.device.station_id)
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
        {pageGroups.map(renderGroup)}
        {pageCount > 1 && (
          <Pagination
            style={{ textAlign: 'center' }}
            current={current}
            pageSize={1}
            total={pageCount}
            showSizeChanger={false}
            showTotal={() => `共 ${shown.length} 台设备`}
            onChange={setPage}
          />
        )}
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
      {(sf.customerShow || sf.show) && (
        <Space wrap style={{ marginBottom: 16 }}>
          {sf.customerShow && (
            <>
              <span>客户</span>
              <CustomerFilter
                value={sf.customerValue}
                onChange={sf.setCustomer}
                customers={sf.customers}
              />
            </>
          )}
          {sf.show && (
            <>
              <span>油站</span>
              <StationFilter
                value={sf.value}
                onChange={sf.setValue}
                stations={sf.stations}
                labelOf={sf.labelOf}
              />
            </>
          )}
        </Space>
      )}
      {body}
    </PageContainer>
  );
};

export default ConsoleDevices;
