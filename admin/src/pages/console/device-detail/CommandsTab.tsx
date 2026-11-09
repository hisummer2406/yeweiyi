import { useIntl } from '@umijs/max';
import { Alert, Button, Space, Table, Tag, Typography } from 'antd';
import type React from 'react';
import { useEffect, useState } from 'react';
import { ApiError } from '@/services/ant-design-pro/apiError';
import { listCommands } from '@/services/ant-design-pro/deviceConfig';
import { commandPhase, targetTank } from '@/utils/deviceConfig';

const PAGE = 20;

const PHASE_COLOR: Record<string, string> = {
  succeeded: 'success',
  failed: 'error',
  rejected: 'error',
  expired: 'warning',
  received: 'processing',
  executing: 'processing',
  accepted: 'default',
  unknown: 'default',
};

const fmtTime = (iso?: string) => (iso ? new Date(iso).toLocaleString() : '—');

/**
 * 操作记录页签：这台设备的命令历史（云端 GET .../commands，最新在前，不给总数，所以只有上一页/下一页）。
 * 状态以云端为准；员工看不到命令历史（后台本来就不对员工开放）。
 */
const CommandsTab: React.FC<{ device: string }> = ({ device }) => {
  const intl = useIntl();
  const t = (id: string, v?: Record<string, string | number>) =>
    intl.formatMessage({ id }, v);
  const [page, setPage] = useState(1);
  const [rows, setRows] = useState<API.ControlCommand[]>();
  const [error, setError] = useState(false);
  const [loading, setLoading] = useState(false);
  const [kick, setKick] = useState(0);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    setError(false);
    // 多要一条判断后面还有没有
    listCommands(device, PAGE + 1, (page - 1) * PAGE)
      .then((r) => alive && setRows(r))
      .catch((err) => {
        if (!alive || (err instanceof ApiError && err.authHandled)) return;
        setError(true);
      })
      .finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
  }, [device, page, kick]);

  const methodLabel = (m: string) => {
    const id = `pages.console.commands.method.${m}`;
    const s = t(id);
    return s === id ? m : s;
  };
  const fieldLabel = (k: string) => {
    const id = `pages.console.settings.field.${k}`;
    const s = t(id);
    return s === id ? k : s;
  };

  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      {error && (
        <Alert
          type="error"
          showIcon
          message={t('pages.console.commands.loadFailed')}
          action={
            <Button onClick={() => setKick((n) => n + 1)}>
              {t('pages.console.settings.retry')}
            </Button>
          }
        />
      )}
      <Table<API.ControlCommand>
        size="small"
        rowKey="commandId"
        loading={loading}
        dataSource={(rows ?? []).slice(0, PAGE)}
        pagination={false}
        locale={{ emptyText: t('pages.console.commands.empty') }}
        columns={[
          {
            title: t('pages.console.commands.col.time'),
            render: (_, c) => fmtTime(c.issuedAt),
          },
          {
            title: t('pages.console.commands.col.method'),
            render: (_, c) => methodLabel(c.method),
          },
          {
            title: t('pages.console.commands.col.target'),
            render: (_, c) => {
              const n =
                targetTank(c.configTarget) ??
                (typeof c.args?.tank === 'number' ? c.args.tank : undefined);
              if (n !== undefined)
                return t('pages.console.settings.tankN', { tank: n });
              return c.configTarget === 'device'
                ? t('pages.console.settings.submit.targetDevice')
                : '—';
            },
          },
          {
            title: t('pages.console.commands.col.args'),
            render: (_, c) =>
              Object.entries(c.args ?? {})
                .filter(([k]) => k !== 'tank')
                .map(([k, v]) => `${fieldLabel(k)}=${String(v)}`)
                .join('；') || '—',
          },
          {
            title: t('pages.console.commands.col.operator'),
            dataIndex: 'operator',
          },
          {
            title: t('pages.console.commands.col.reason'),
            dataIndex: 'reason',
            ellipsis: true,
          },
          {
            title: t('pages.console.commands.col.status'),
            render: (_, c) => {
              const phase = commandPhase(c);
              return (
                <Space direction="vertical" size={0}>
                  <Tag color={PHASE_COLOR[phase]}>
                    {t(`pages.console.commands.status.${phase}`)}
                  </Tag>
                  {(c.errorReason || c.errorCode) && (
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {c.errorReason || c.errorCode}
                    </Typography.Text>
                  )}
                </Space>
              );
            },
          },
        ]}
      />
      <Space>
        <Button
          disabled={page === 1 || loading}
          onClick={() => setPage((p) => p - 1)}
        >
          {t('pages.console.commands.prev')}
        </Button>
        <Button
          disabled={loading || (rows?.length ?? 0) <= PAGE}
          onClick={() => setPage((p) => p + 1)}
        >
          {t('pages.console.commands.next')}
        </Button>
        <Button type="link" onClick={() => setKick((n) => n + 1)}>
          {t('pages.console.commands.refresh')}
        </Button>
      </Space>
    </Space>
  );
};

export default CommandsTab;
