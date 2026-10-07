import { ReloadOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import {
  Alert,
  Button,
  DatePicker,
  Empty,
  Skeleton,
  Space,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import type React from 'react';
import { ApiError } from '@/services/ant-design-pro/apiError';
import { hasDegradation, type Notice } from '@/utils/records';
import type { RecordsQueryState } from './useRecordsQuery';

type Props<T> = {
  state: RecordsQueryState<T>;
  /** 由 summarizeAlarms / summarizeStock 生成；没有数据时传空数组 */
  notices: Notice[];
  /** 数据里有没有行（过滤方向之后） */
  hasRows: boolean;
  /** 真的没有记录时显示的文案 id */
  emptyId: string;
  /** 只在有行时渲染 */
  children: React.ReactNode;
};

/**
 * 报警与出入库记录共用的外壳：时间范围、刷新、固定说明、降级提示，以及加载/失败/空状态。
 * 关键约束：不能把「加载失败」或「云端说不完整/读不了」显示成「没有记录」——
 * 只有在没有任何非说明类提示、也没有错误时，空列表才显示空状态；有降级提示而没有行时只显示提示。
 */
function RecordsFrame<T>({
  state,
  notices,
  hasRows,
  emptyId,
  children,
}: Props<T>) {
  const intl = useIntl();
  const t = (id: string, values?: Record<string, string>) =>
    intl.formatMessage({ id }, values);
  const { data, loading, refreshing, error, range, setRange, reload } = state;
  // 鉴权失败已由全局处理回登录页，这里不再提示
  const silent = error instanceof ApiError && error.authHandled;

  let body: React.ReactNode = null;
  if (loading) {
    body = <Skeleton active />;
  } else if (error && !data) {
    body = silent ? null : (
      <Alert
        type="error"
        showIcon
        title={t('pages.console.records.loadFailed')}
        action={
          <Button size="small" onClick={reload}>
            {t('pages.console.records.retry')}
          </Button>
        }
      />
    );
  } else if (data) {
    const degraded = hasDegradation(notices);
    body = (
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        {!!error && !silent && (
          <Alert
            type="warning"
            showIcon
            title={t('pages.console.records.refreshFailed')}
          />
        )}
        {notices.map((n) => (
          <Alert
            key={n.id}
            type={n.level}
            showIcon
            title={t(`pages.console.records.notice.${n.id}`, n.values)}
            description={
              n.id === 'firmware' && (n.values.device || n.values.sample)
                ? t('pages.console.records.notice.firmware.detail', n.values)
                : undefined
            }
          />
        ))}
        {hasRows ? (
          children
        ) : degraded ? null : (
          <Empty description={t(emptyId)} />
        )}
      </Space>
    );
  }

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Space wrap>
        <span>{t('pages.console.records.range.label')}</span>
        <DatePicker.RangePicker
          showTime
          allowClear
          value={range ? [dayjs(range.begin), dayjs(range.end)] : null}
          onChange={(v) =>
            setRange(
              v?.[0] && v[1]
                ? { begin: v[0].toDate(), end: v[1].toDate() }
                : undefined,
            )
          }
        />
        <Button icon={<ReloadOutlined />} loading={refreshing} onClick={reload}>
          {t('pages.console.records.refresh')}
        </Button>
      </Space>
      <Typography.Text type="secondary">
        {t('pages.console.records.assignedNote')}
      </Typography.Text>
      {body}
    </Space>
  );
}

export default RecordsFrame;
