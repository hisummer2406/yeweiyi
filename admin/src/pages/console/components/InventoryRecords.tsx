import { useIntl } from '@umijs/max';
import type { ColumnsType } from 'antd/es/table';
import type React from 'react';
import { listInventoryRecords } from '@/services/ant-design-pro/syncedRecords';
import { mediumText } from '@/utils/oilType';
import { DASH, num } from '@/utils/records';
import { fmtTime } from '@/utils/telemetry';
import SyncedRecords from './SyncedRecords';

const rowKey = (r: API.InventoryRecord) => `${r.recordId}|${r.tank}`;
const groupKey = (r: API.InventoryRecord) => r.recordId;

/**
 * 某台设备的库存记录（GET /api/stock-records，cloud#85）：每个 (库存记录, 罐) 一行，同一次库存的行同底色。
 * 列顺序同设备显示屏与小程序：罐号、介质、库存生成时间、液位、容积、水高、温度、质量。液位、水高单位是 mm。
 */
const InventoryRecords: React.FC<{ device: string }> = ({ device }) => {
  const intl = useIntl();
  const t = (id: string) => intl.formatMessage({ id });
  const columns: ColumnsType<API.InventoryRecord> = [
    { title: t('pages.console.records.col.tank'), dataIndex: 'tank' },
    {
      title: t('pages.console.records.col.medium'),
      render: (_, r) => mediumText(r.oilTypeIndex, r.oilType, t),
    },
    {
      title: t('pages.console.records.col.stockTime'),
      render: (_, r) => (r.time ? fmtTime(r.time) : DASH),
    },
    ...(
      [
        ['levelMm', 'levelMm'],
        ['volumeL', 'volumeL'],
        ['waterMm', 'waterLevelMm'],
        ['tempC', 'tempC'],
        ['weightKg', 'weightKg'],
      ] as const
    ).map(([col, field]) => ({
      title: t(`pages.console.records.col.${col}`),
      align: 'right' as const,
      render: (_: unknown, r: API.InventoryRecord) => num(r[field]),
    })),
  ];
  return (
    <SyncedRecords<API.InventoryRecord>
      device={device}
      fetcher={listInventoryRecords}
      columns={columns}
      rowKey={rowKey}
      groupKey={groupKey}
      emptyId="pages.console.records.empty.inventory"
    />
  );
};

export default InventoryRecords;
