import { useIntl } from '@umijs/max';
import { Alert, Table, Tag } from 'antd';
import type React from 'react';
import { useEffect, useMemo, useState } from 'react';
import { listStockEvents } from '@/services/ant-design-pro/stock';
import {
  label,
  STOCK_DIRECTION_LABELS,
  STOCK_SOURCE_LABELS,
} from '@/utils/labels';
import { mediumText } from '@/utils/oilType';
import { filterRows, num, summarizeStock } from '@/utils/records';
import { fmt, fmtTime } from '@/utils/telemetry';
import RecordsFrame from './RecordsFrame';
import TankFilter from './TankFilter';
import { REFRESH_MS, useRecordsQuery } from './useRecordsQuery';
import { useTankSelection } from './useTankSelection';

type Props = {
  device: string;
  /** 入库页 IN、出库页 OUT；设备详情页签不传，两个方向都显示。由云端先筛选后分页（#84） */
  direction?: 'IN' | 'OUT';
};

const range = (a: number, b: number) => `${fmt(a)} → ${fmt(b)}`;

/**
 * 某台设备的出入库记录（GET /api/oil-in-out）：最新在前，每页 20 条由云端分页（limit/offset）。
 * 方向与罐号交给云端先筛选后分页（#84），分页针对筛选后的记录；按行再过滤一遍只为兼容旧云端。
 * 云端的降级信号（502 固件未升级、unparsable、legacySkipped、partial、truncated 等）由 summarizeStock
 * 转成提示；有降级而没有行时只显示提示，不显示成「没有出入库记录」。
 */
const StockRecords: React.FC<Props> = ({ device, direction }) => {
  const intl = useIntl();
  const t = (id: string) => intl.formatMessage({ id });
  const [error, setError] = useState<unknown>();
  const { tank, refused, setTank } = useTankSelection(device, error);
  const state = useRecordsQuery(listStockEvents, device, REFRESH_MS, {
    tank,
    direction,
  });
  useEffect(() => setError(state.error), [state.error]);
  const rows = useMemo(
    () => filterRows(state.data?.rows ?? [], { tank, direction }),
    [state.data, tank, direction],
  );
  const notices = useMemo(
    () => (state.data ? summarizeStock(state.data) : []),
    [state.data],
  );

  return (
    <RecordsFrame
      filters={<TankFilter device={device} value={tank} onChange={setTank} />}
      banner={
        refused !== undefined && (
          <Alert
            type="warning"
            showIcon
            title={intl.formatMessage(
              { id: 'pages.console.records.tankRefused' },
              { n: refused },
            )}
          />
        )
      }
      state={state}
      notices={notices}
      hasRows={rows.length > 0}
      emptyId="pages.console.records.empty.stock"
    >
      <Table<API.StockEvent>
        size="middle"
        rowKey="eventId"
        dataSource={rows}
        pagination={false}
        loading={state.refreshing && !state.loading}
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
            title: t('pages.console.records.col.medium'),
            render: (_, r) => mediumText(r.oilTypeIndex, r.oilType, t),
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
            // 设备给的交付质量（cloud#85）；旧记录 null 显示「—」，不能用前后重量差代替（差值含期间销售）
            title: t('pages.console.records.col.deliveredKg'),
            dataIndex: 'deliveredWeightKg',
            align: 'right',
            render: (v?: number | null) => num(v),
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
