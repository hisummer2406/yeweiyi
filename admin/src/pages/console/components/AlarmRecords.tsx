import { useIntl } from '@umijs/max';
import { Alert, Table, Tag } from 'antd';
import type React from 'react';
import { useEffect, useMemo, useState } from 'react';
import { listAlarms } from '@/services/ant-design-pro/alarms';
import {
  ALARM_SEVERITY_LABELS,
  ALARM_STATUS_LABELS,
  ALARM_TYPE_LABELS,
  label,
} from '@/utils/labels';
import { mediumText } from '@/utils/oilType';
import { filterRows, summarizeAlarms } from '@/utils/records';
import { fmt, fmtTime } from '@/utils/telemetry';
import RecordsFrame from './RecordsFrame';
import TankFilter from './TankFilter';
import { REFRESH_MS, useRecordsQuery } from './useRecordsQuery';
import { useTankSelection } from './useTankSelection';

/**
 * 某台设备的报警记录（GET /api/alarms）：最新在前，每页 20 条由云端分页（limit/offset）。
 * 一次状态变化一行（ACTIVE 与之后的 CLEARED 是两行，共用 alarmId），云端没有折叠。
 * 可按罐号筛选，由云端先筛选后分页（#84）。
 */
const AlarmRecords: React.FC<{ device: string }> = ({ device }) => {
  const intl = useIntl();
  const t = (id: string) => intl.formatMessage({ id });
  const [error, setError] = useState<unknown>();
  const { tank, refused, setTank } = useTankSelection(device, error);
  const state = useRecordsQuery(listAlarms, device, REFRESH_MS, { tank });
  useEffect(() => setError(state.error), [state.error]);
  const rows = useMemo(
    () => filterRows(state.data?.rows ?? [], { tank }),
    [state.data, tank],
  );
  const notices = useMemo(
    () => (state.data ? summarizeAlarms(state.data) : []),
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
      emptyId="pages.console.records.empty.alarms"
    >
      <Table<API.Alarm>
        size="middle"
        rowKey={(r) => `${r.alarmId}|${r.time}|${r.tank}|${r.status}|${r.type}`}
        dataSource={rows}
        pagination={false}
        loading={state.refreshing && !state.loading}
        columns={[
          {
            title: t('pages.console.records.col.time'),
            dataIndex: 'time',
            render: (v: string) => fmtTime(v),
          },
          { title: t('pages.console.records.col.tank'), dataIndex: 'tank' },
          {
            // 报警发生时的介质（cloud#85）；设备级报警与旧记录没有，显示「—」，不拿罐当前油品代替
            title: t('pages.console.records.col.medium'),
            render: (_, r) => mediumText(r.oilTypeIndex, r.oilType, t),
          },
          {
            title: t('pages.console.records.col.type'),
            dataIndex: 'type',
            render: (v: string) => label(ALARM_TYPE_LABELS, v),
          },
          {
            title: t('pages.console.records.col.severity'),
            dataIndex: 'severity',
            render: (v: string) => label(ALARM_SEVERITY_LABELS, v),
          },
          {
            title: t('pages.console.records.col.status'),
            dataIndex: 'status',
            render: (v: string) => (
              <Tag color={v === 'ACTIVE' ? 'error' : 'default'}>
                {label(ALARM_STATUS_LABELS, v)}
              </Tag>
            ),
          },
          { title: t('pages.console.records.col.text'), dataIndex: 'text' },
          {
            title: t('pages.console.records.col.value'),
            dataIndex: 'value',
            render: (v?: number) => (v === undefined ? '--' : fmt(v)),
          },
        ]}
      />
    </RecordsFrame>
  );
};

export default AlarmRecords;
