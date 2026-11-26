import { ReloadOutlined } from '@ant-design/icons';
import { PageContainer, ProCard } from '@ant-design/pro-components';
import { getIntl, history } from '@umijs/max';
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
import { fallbackMessage } from '@/requestErrorConfig';
import { listDevices } from '@/services/ant-design-pro/devices';
import {
  getLatestTelemetry,
  isTelemetryEmpty,
  type TelemetryEmpty,
  telemetryEmptyMessageId,
} from '@/services/ant-design-pro/telemetry';
import {
  createLimiter,
  OVERVIEW_PAGE_SIZE,
  packPages,
  TELEMETRY_CONCURRENCY,
} from '@/utils/overviewPaging';
import { tankShapeOf } from '@/utils/tankShape';
import { fmtTime, LIVE_TELEMETRY_MS } from '@/utils/telemetry';
import TankCard from '../components/TankCard';
import {
  CustomerFilter,
  StationFilter,
  useStationFilter,
} from '../components/useStationFilter';
import { useTankShapes } from '../components/useTankShapes';

/** 设备列表（在线状态、油站归属）的刷新间隔；读数另按 LIVE_TELEMETRY_MS 只刷当前页（#62）。 */
const LIST_REFRESH_MS = 30_000;

/** 一台设备在总览里的状态。每台独立，一台失败不影响其它。 */
type Group = {
  device: API.DeviceRow;
  /** idle：还没轮到取遥测（不在当前页）；翻到该页时才请求 */
  state: 'idle' | 'loading' | 'ok' | 'never' | 'error';
  /** state 为 never 时是哪种空状态（yeweiyi-cloud#79） */
  empty?: TelemetryEmpty;
  telemetry?: API.LatestTelemetry;
  error?: string;
};

/**
 * 设备总览——PC 端对应小程序首页（pages/devices）：
 * GET /api/devices 取调用者可见的设备，再对**当前页**的每台取最新遥测（后端没有批量接口）。
 * 分页只在前端：设备行很轻，列表一次取全；遥测按页、限并发请求，标签页不可见时不轮询。
 * 读数每 5 秒只刷当前页（不重拉列表），列表 30 秒一刷；同一台设备上次请求未回时跳过本轮，
 * 失败时保留上次成功的读数（静默），只有从没取到过才显示错误。
 */
const ConsoleDevices: React.FC = () => {
  const [groups, setGroups] = useState<Group[] | null>(null);
  const [listError, setListError] = useState('');
  const [refreshing, setRefreshing] = useState(false);
  const [page, setPage] = useState(1);
  // 当前页上的设备编号：轮询只刷新它们
  const visibleRef = useRef<string[]>([]);
  // 列表请求每次自增；旧一轮的列表晚到时丢弃，避免覆盖新数据。
  const seq = useRef(0);
  // 卸载时自增：之后到达的遥测响应一律丢弃
  const gen = useRef(0);
  // 正在取遥测（含排队）的设备：同一台同一时刻只有一个请求，所以不会乱序，也不会堆积
  const inflight = useRef(new Set<string>());
  // 所有轮次共享的并发上限
  const limiter = useRef(createLimiter(TELEMETRY_CONCURRENCY)).current;

  const patch = useCallback(
    (
      name: string,
      myGen: number,
      p: Partial<Group> | ((g: Group) => Partial<Group>),
    ) => {
      if (myGen !== gen.current) return;
      setGroups((prev) =>
        prev
          ? prev.map((g) =>
              g.device.device_name === name
                ? { ...g, ...(typeof p === 'function' ? p(g) : p) }
                : g,
            )
          : prev,
      );
    },
    [],
  );

  const fetchDevice = useCallback(
    async (name: string, myGen: number) => {
      try {
        const t = await limiter(() => getLatestTelemetry(name));
        patch(
          name,
          myGen,
          isTelemetryEmpty(t)
            ? {
                state: 'never',
                empty: t,
                telemetry: undefined,
                error: undefined,
              }
            : { state: 'ok', telemetry: t, error: undefined },
        );
      } catch (e: any) {
        // 已有读数（或已知从未上报）时静默保留上次成功的结果与上报时间（#62），只有从没取到过才显示错误。
        // 不显示云端原文（yeweiyi-cloud#79）：按 code/状态给本地化兜底；没有响应时 status 为 0
        patch(name, myGen, (g) =>
          g.state === 'ok' || g.state === 'never'
            ? {}
            : {
                state: 'error',
                error: getIntl().formatMessage(
                  e?.response
                    ? fallbackMessage(e.response.status, e.response.data?.code)
                    : {
                        id: 'app.request.noResponse',
                        defaultMessage:
                          '网络不可用或请求超时，请检查网络后重试。',
                      },
                ),
              },
        );
      } finally {
        inflight.current.delete(name);
      }
    },
    [patch, limiter],
  );

  /** 取这些设备的最新遥测；上次请求还没回的设备跳过本轮。 */
  const pollDevices = useCallback(
    async (names: string[]) => {
      const fresh = names.filter((n) => !inflight.current.has(n));
      for (const n of fresh) inflight.current.add(n);
      const myGen = gen.current;
      await Promise.all(fresh.map((n) => fetchDevice(n, myGen)));
    },
    [fetchDevice],
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
      await pollDevices(visibleRef.current.filter((n) => existing.has(n)));
    } catch (e: any) {
      if (mySeq !== seq.current) return;
      setListError(
        e?.response?.data?.error || e?.message || '加载设备列表失败',
      );
      setGroups((prev) => prev ?? []);
    } finally {
      if (mySeq === seq.current) setRefreshing(false);
    }
  }, [pollDevices]);

  useEffect(() => {
    load();
    // 标签页不可见时不轮询；回到前台立即补一次
    const listTimer = setInterval(() => {
      if (!document.hidden) load();
    }, LIST_REFRESH_MS);
    // 读数：只刷当前页，不重拉列表（#62）
    const liveTimer = setInterval(() => {
      if (!document.hidden) void pollDevices(visibleRef.current);
    }, LIVE_TELEMETRY_MS);
    const onVisible = () => {
      if (!document.hidden) load();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      clearInterval(listTimer);
      clearInterval(liveTimer);
      document.removeEventListener('visibilitychange', onVisible);
      seq.current++; // 卸载后丢弃在途响应
      gen.current++;
    };
  }, [load, pollDevices]);

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
  const shapes = useTankShapes(pageGroups.map((g) => g.device.device_name));

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
    setGroups((prev) =>
      prev
        ? prev.map((g) =>
            names.includes(g.device.device_name) && g.state === 'idle'
              ? { ...g, state: 'loading' }
              : g,
          )
        : prev,
    );
    void pollDevices(names);
  }, [idleKey, pollDevices]);

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
      // 窄屏下标题与上报时间整块换行，而不是把油站名、设备编号、在线状态逐字挤碎（#21 残留）
      styles={{
        header: { flexWrap: 'wrap', rowGap: 4 },
        title: { flex: '1 1 auto', minWidth: 0 },
      }}
      title={
        <Space wrap size={[8, 0]}>
          {typeof g.device.station_id === 'number' ? (
            <>
              <Typography.Text strong>
                {sf.labelOf(g.device.station_id)}
              </Typography.Text>
              <Typography.Text
                type="secondary"
                style={{ whiteSpace: 'nowrap' }}
              >
                {g.device.device_name}
              </Typography.Text>
            </>
          ) : (
            <Typography.Text strong style={{ whiteSpace: 'nowrap' }}>
              {g.device.device_name}
            </Typography.Text>
          )}
          <Badge
            status={g.device.is_online ? 'success' : 'default'}
            text={g.device.is_online ? '在线' : '离线'}
            style={{ whiteSpace: 'nowrap' }}
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
      {g.state === 'never' && (
        <Empty
          description={
            g.empty
              ? getIntl().formatMessage(
                  { id: telemetryEmptyMessageId(g.empty) },
                  { time: fmtTime(g.empty.lastReportedAt) },
                )
              : '该设备还没有上报过数据'
          }
        />
      )}
      {g.state === 'error' && (
        <Alert
          type="error"
          showIcon
          message={g.error}
          action={
            <Button
              size="small"
              onClick={() => {
                patch(g.device.device_name, gen.current, { state: 'loading' });
                void pollDevices([g.device.device_name]);
              }}
            >
              重试
            </Button>
          }
        />
      )}
      {g.state === 'ok' && g.telemetry && (
        <Row gutter={[8, 8]}>
          {g.telemetry.tanks.map((t) => (
            <Col key={t.index} xs={24} sm={12} lg={8} xl={6}>
              <TankCard
                tank={t}
                shapeInfo={tankShapeOf(
                  shapes.states[g.device.device_name],
                  t.index,
                )}
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
          onClick={() => {
            void load();
            shapes.refresh();
          }}
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
