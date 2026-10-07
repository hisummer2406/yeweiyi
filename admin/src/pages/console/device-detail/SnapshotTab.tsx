import { ReloadOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import {
  Alert,
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
import { getLatestTelemetry } from '@/services/ant-design-pro/telemetry';
import { fmtTime } from '@/utils/telemetry';
import TankCard from '../components/TankCard';
import { REFRESH_MS } from '../components/useRecordsQuery';

type State =
  | { kind: 'loading' }
  | { kind: 'ok'; telemetry: API.LatestTelemetry }
  | { kind: 'never' }
  | { kind: 'forbidden' }
  | { kind: 'error' };

/**
 * 区分「无权查看这台设备」与账号已失效：getLatestTelemetry 对 401 与账号失效的 403 已转交全局回登录页，
 * 这里只把「其余的 403」当作无权查看；404 在 getLatestTelemetry 里已变成 null（从未上报）。
 */
const forbiddenOrSilent = (e: unknown): 'forbidden' | 'silent' | 'error' => {
  const r = (e as { response?: { status?: number; data?: { error?: string } } })
    ?.response;
  if (r?.status === 401) return 'silent';
  if (r?.status === 403) {
    return r.data?.error === 'not authorized' ? 'silent' : 'forbidden';
  }
  return 'error';
};

/**
 * 设备详情的「实时数据」页签：最新遥测（GET /api/devices/:device/telemetry/latest），每个油罐一张
 * 与设备总览相同的卡片。404 是正常状态「从未上报」，403 是「无权查看」，两者都不是普通错误。
 * 每 30 秒自动刷新；刷新时保留旧数据不闪回骨架屏，迟到的旧响应丢弃。
 */
const SnapshotTab: React.FC<{ device: string }> = ({ device }) => {
  const intl = useIntl();
  const t = (id: string, values?: Record<string, string>) =>
    intl.formatMessage({ id }, values);
  const [state, setState] = useState<State>({ kind: 'loading' });
  const [refreshing, setRefreshing] = useState(false);
  const seq = useRef(0);

  const load = useCallback(
    async (fresh: boolean) => {
      const mine = ++seq.current;
      if (fresh) setState({ kind: 'loading' });
      setRefreshing(true);
      try {
        const telemetry = await getLatestTelemetry(device);
        if (mine !== seq.current) return;
        setState(telemetry ? { kind: 'ok', telemetry } : { kind: 'never' });
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
      body = <Empty description={t('pages.console.detail.snapshot.never')} />;
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
    case 'ok':
      body = (
        <Row gutter={[16, 16]}>
          {state.telemetry.tanks.map((tank) => (
            <Col key={tank.index} xs={24} md={12} xl={8}>
              <TankCard tank={tank} onClick={() => {}} />
            </Col>
          ))}
        </Row>
      );
      break;
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
