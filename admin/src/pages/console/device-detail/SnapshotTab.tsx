import { ReloadOutlined } from '@ant-design/icons';
import { useIntl, useSearchParams } from '@umijs/max';
import {
  Alert,
  Button,
  Empty,
  Segmented,
  Skeleton,
  Space,
  Typography,
} from 'antd';
import type React from 'react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { isAccountRevoked } from '@/requestErrorConfig';
import {
  getLatestTelemetry,
  isTelemetryEmpty,
  type TelemetryEmpty,
  telemetryEmptyMessageId,
} from '@/services/ant-design-pro/telemetry';
import { parseTankParam, pickTank } from '@/utils/tankPick';
import { fmtTime } from '@/utils/telemetry';
import { REFRESH_MS } from '../components/useRecordsQuery';
import TankDetail from './TankDetail';

type State =
  | { kind: 'loading' }
  | { kind: 'ok'; telemetry: API.LatestTelemetry }
  | { kind: 'never'; empty: TelemetryEmpty }
  | { kind: 'forbidden' }
  | { kind: 'error' };

/**
 * 区分「无权查看这台设备」与账号已失效：getLatestTelemetry 对 401 与账号失效的 403 已转交全局回登录页，
 * 这里只把「其余的 403」当作无权查看；404 在 getLatestTelemetry 里已变成空状态（从未上报/已过保留期/接入未就绪）。
 */
const forbiddenOrSilent = (e: unknown): 'forbidden' | 'silent' | 'error' => {
  const r = (e as { response?: { status?: number; data?: { error?: string } } })
    ?.response;
  if (r?.status === 401) return 'silent';
  if (r?.status === 403) {
    return isAccountRevoked(e) ? 'silent' : 'forbidden';
  }
  return 'error';
};

/**
 * 设备详情的「实时数据」页签：最新遥测（GET /api/devices/:device/telemetry/latest）。**一次只显示一个罐**（同小程序）：
 * `?tank=` 是总览里点的那个罐的协议罐号，缺省显示最小罐号；请求的罐不可见就提示并改显示最小罐号。多个罐时上方有切换，
 * 切换只改 URL 的 tank。404 是正常状态「从未上报」，403 是「无权查看」，两者都不是普通错误。
 * 每 30 秒自动刷新；刷新时保留旧数据不闪回骨架屏，迟到的旧响应丢弃。
 */
const SnapshotTab: React.FC<{ device: string }> = ({ device }) => {
  const intl = useIntl();
  const t = (id: string, values?: Record<string, string>) =>
    intl.formatMessage({ id }, values);
  const [state, setState] = useState<State>({ kind: 'loading' });
  const [refreshing, setRefreshing] = useState(false);
  const [params, setParams] = useSearchParams();
  const requestedTank = parseTankParam(params.get('tank'));
  const seq = useRef(0);

  const load = useCallback(
    async (fresh: boolean) => {
      const mine = ++seq.current;
      if (fresh) setState({ kind: 'loading' });
      setRefreshing(true);
      try {
        const telemetry = await getLatestTelemetry(device);
        if (mine !== seq.current) return;
        setState(
          isTelemetryEmpty(telemetry)
            ? { kind: 'never', empty: telemetry }
            : { kind: 'ok', telemetry },
        );
      } catch (e) {
        if (mine !== seq.current) return;
        const why = forbiddenOrSilent(e);
        if (why === 'forbidden') setState({ kind: 'forbidden' });
        else if (why === 'error') {
          // 后台刷新失败时保留上一次成功的数据，只有没有数据时才显示错误
          setState((prev) => (prev.kind === 'ok' ? prev : { kind: 'error' }));
        }
      } finally {
        if (mine === seq.current) setRefreshing(false);
      }
    },
    [device],
  );

  useEffect(() => {
    void load(true);
    const timer = setInterval(() => void load(false), REFRESH_MS);
    return () => {
      clearInterval(timer);
      seq.current++; // 卸载或换设备后丢弃在途响应
    };
  }, [load]);

  let body: React.ReactNode;
  switch (state.kind) {
    case 'loading':
      body = <Skeleton active />;
      break;
    case 'never':
      body = (
        <Empty
          description={t(telemetryEmptyMessageId(state.empty), {
            time: fmtTime(state.empty.lastReportedAt),
          })}
        />
      );
      break;
    case 'forbidden':
      body = (
        <Alert
          type="warning"
          showIcon
          title={t('pages.console.detail.snapshot.forbidden')}
        />
      );
      break;
    case 'error':
      body = (
        <Alert
          type="error"
          showIcon
          title={t('pages.console.detail.snapshot.failed')}
          action={
            <Button size="small" onClick={() => void load(true)}>
              {t('pages.console.records.retry')}
            </Button>
          }
        />
      );
      break;
    case 'ok': {
      const tanks = [...state.telemetry.tanks].sort(
        (a, b) => a.index - b.index,
      );
      const pick = pickTank(
        tanks.map((x) => x.index),
        requestedTank,
      );
      if (pick.kind === 'none') {
        body = (
          <Empty description={t('pages.console.detail.snapshot.noTank')} />
        );
        break;
      }
      const tank = tanks.find((x) => x.index === pick.tank) as API.Tank;
      body = (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          {pick.hidden !== undefined && (
            <Alert
              type="warning"
              showIcon
              title={intl.formatMessage(
                { id: 'pages.console.detail.snapshot.tankHidden' },
                { index: pick.hidden, shown: pick.tank },
              )}
            />
          )}
          {tanks.length > 1 && (
            <Segmented
              aria-label={t('pages.console.detail.snapshot.switch')}
              value={pick.tank}
              options={tanks.map((x) => ({
                value: x.index,
                label: `${x.index}号 ${x.oilType || '--'}`,
              }))}
              onChange={(v) => {
                const next = new URLSearchParams(params);
                next.set('tank', String(v));
                setParams(next, { replace: true });
              }}
            />
          )}
          {/* 罐号 + 介质在 TankDetail 的显示器样式标题条里（#21），这里不再重复一行标题 */}
          <TankDetail tank={tank} />
        </Space>
      );
      break;
    }
  }

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Space>
        <Button
          icon={<ReloadOutlined />}
          loading={refreshing}
          onClick={() => void load(false)}
        >
          {t('pages.console.records.refresh')}
        </Button>
        {state.kind === 'ok' && (
          <Typography.Text type="secondary">
            {t('pages.console.detail.snapshot.reportedAt', {
              time: fmtTime(state.telemetry.ts),
            })}
          </Typography.Text>
        )}
      </Space>
      {body}
    </Space>
  );
};

export default SnapshotTab;
