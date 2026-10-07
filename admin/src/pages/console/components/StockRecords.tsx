import { useIntl } from '@umijs/max';
import { Table, Tag } from 'antd';
import type React from 'react';
import { useMemo } from 'react';
import { listStockEvents } from '@/services/ant-design-pro/stock';
import {
  label,
  STOCK_DIRECTION_LABELS,
  STOCK_SOURCE_LABELS,
} from '@/utils/labels';
import { filterByDirection, summarizeStock } from '@/utils/records';
import { fmt, fmtTime } from '@/utils/telemetry';
import RecordsFrame from './RecordsFrame';
import { useRecordsQuery } from './useRecordsQuery';

type Props = {
  device: string;
  /** 入库页 IN、出库页 OUT；设备详情页签不传，两个方向都显示（云端没有方向过滤，前端过滤） */
  direction?: 'IN' | 'OUT';
};

const range = (a: number, b: number) => `${fmt(a)} → ${fmt(b)}`;

/**
 * 某台设备的出入库记录（GET /api/oil-in-out）：最新在前，一次取窗口内最新 500 条，前端分页。
 * 云端的降级信号（502 固件未升级、unparsable、legacySkipped、partial、truncated 等）由 summarizeStock
 * 转成提示；有降级而没有行时只显示提示，不显示成「没有出入库记录」。
 */
const StockRecords: React.FC<Props> = ({ device, direction }) => {
  const intl = useIntl();
  const t = (id: string) => intl.formatMessage({ id });
  const state = useRecordsQuery(listStockEvents, device);
  const rows = useMemo(
    () => filterByDirection(state.data?.rows ?? [], direction),
    [state.data, direction],
  );
  const notices = useMemo(
    () => (state.data ? summarizeStock(state.data) : []),
    [state.data],
  );

  return (
    <RecordsFrame
      state={state}
      notices={notices}
      hasRows={rows.length > 0}
      emptyId="pages.console.records.empty.stock"
    >
      <Table<API.StockEvent>
        key={`${state.version}|${direction ?? ''}`}
        size="middle"
        rowKey="eventId"
        dataSource={rows}
        pagination={{ pageSize: 20, showSizeChanger: false }}
        scroll={{ x: 'max-content' }}
        columns={[
          {
            title: t('pages.console.records.col.endTime'),
            dataIndex: 'time',
            render: (v: string) => fmtTime(v),
          },
          { title: t('pages.console.records.col.tank'), dataIndex: 'tank' },
          {
            title: t('pages.console.records.col.direction'),
            dataIndex: 'direction',
            render: (v: string) => (
              <Tag color={v === 'IN' ? 'blue' : 'orange'}>
                {label(STOCK_DIRECTION_LABELS, v)}
              </Tag>
            ),
          },
          {
            title: t('pages.console.records.col.oilType'),
            dataIndex: 'oilType',
            render: (v?: string) => v || '--',
          },
          {
            title: t('pages.console.records.col.source'),
            dataIndex: 'source',
            render: (v: string) => label(STOCK_SOURCE_LABELS, v),
          },
          {
            title: t('pages.console.records.col.delivered'),
            dataIndex: 'deliveredVolumeL',
            render: (v: number) => fmt(v),
          },
          {
            title: t('pages.console.records.col.net'),
            dataIndex: 'netVolumeL',
            render: (v: number) => fmt(v),
          },
          {
            title: t('pages.console.records.col.level'),
            render: (_, r) => range(r.beginLevelMm, r.endLevelMm),
          },
          {
            title: t('pages.console.records.col.volume'),
            render: (_, r) => range(r.beginVolumeL, r.endVolumeL),
          },
        ]}
      />
    </RecordsFrame>
  );
};

export default StockRecords;
