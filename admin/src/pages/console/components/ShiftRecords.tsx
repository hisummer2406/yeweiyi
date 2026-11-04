import { useIntl } from '@umijs/max';
import { Space, Tag } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type React from 'react';
import { listShiftRecords } from '@/services/ant-design-pro/syncedRecords';
import { mediumText } from '@/utils/oilType';
import { DASH, num } from '@/utils/records';
import { fmtTime } from '@/utils/telemetry';
import SyncedRecords from './SyncedRecords';

const rowKey = (r: API.ShiftRecord) => `${r.shiftId}|${r.tank}`;
const groupKey = (r: API.ShiftRecord) => r.shiftId;
const time = (v: string | null) => (v ? fmtTime(v) : DASH);

/**
 * 某台设备的交班记录（GET /api/shift-records，cloud#85）：每个 (班, 罐) 一行，同一班的行同底色。
 * 列顺序同设备显示屏与小程序：罐号、介质、交班时间（endTime）、接班时间（beginTime）、前高、前量、后高、后量、出库量(L/kg)。
 * 进行中的班（status=OPEN）没有交班时间和结束/出库数值，显示「—」不显示 0。
 */
const ShiftRecords: React.FC<{ device: string }> = ({ device }) => {
  const intl = useIntl();
  const t = (id: string) => intl.formatMessage({ id });
  const columns: ColumnsType<API.ShiftRecord> = [
    { title: t('pages.console.records.col.tank'), dataIndex: 'tank' },
    {
      title: t('pages.console.records.col.medium'),
      render: (_, r) => mediumText(r.oilTypeIndex, r.oilType, t),
    },
    {
      title: t('pages.console.records.col.shiftTime'),
      render: (_, r) =>
        r.status === 'OPEN' ? (
          <Space size={4}>
            {time(r.endTime)}
            <Tag color="processing">{t('pages.console.records.shiftOpen')}</Tag>
          </Space>
        ) : (
          time(r.endTime)
        ),
    },
    {
      title: t('pages.console.records.col.takeoverTime'),
      render: (_, r) => time(r.beginTime),
    },
    ...(
      [
        ['beginLevel', 'beginLevelMm'],
        ['beginVolume', 'beginVolumeL'],
        ['endLevel', 'endLevelMm'],
        ['endVolume', 'endVolumeL'],
        ['outQtyL', 'outVolumeL'],
        ['outQtyKg', 'outWeightKg'],
      ] as const
    ).map(([col, field]) => ({
      title: t(`pages.console.records.col.${col}`),
      align: 'right' as const,
      render: (_: unknown, r: API.ShiftRecord) => num(r[field]),
    })),
  ];
  return (
    <SyncedRecords<API.ShiftRecord>
      device={device}
      fetcher={listShiftRecords}
      columns={columns}
      rowKey={rowKey}
      groupKey={groupKey}
      emptyId="pages.console.records.empty.shift"
    />
  );
};

export default ShiftRecords;
