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
import { filterRows, summarizeAlarms } from '@/utils/records';
import { fmt, fmtTime } from '@/utils/telemetry';
import RecordsFrame from './RecordsFrame';
import TankFilter from './TankFilter';
import { REFRESH_MS, useRecordsQuery } from './useRecordsQuery';
import { useTankSelection } from './useTankSelection';

/**
 * 某台设备的报警记录（GET /api/alarms）：最新在前，一次取窗口内最新 500 条，前端分页。
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
    <>
      <TankFilter device={device} value={tank} onChange={setTank} />
      {refused !== undefined && (
        <Alert
          type="warning"
          showIcon
          style={{ marginTop: 12 }}
          title={intl.formatMessage(
            { id: 'pages.console.records.tankRefused' },
            { n: refused },
          )}
        />
      )}
      <RecordsFrame
        state={state}
        notices={notices}
        hasRows={rows.length > 0}
        emptyId="pages.console.records.empty.alarms"
      >
        <Table<API.Alarm>
          key={state.version}
          size="middle"
          rowKey={(r) =>
            `${r.alarmId}|${r.time}|${r.tank}|${r.status}|${r.type}`
          }
          dataSource={rows}
          pagination={{ pageSize: 20, showSizeChanger: false }}
          columns={[
            {
              title: t('pages.console.records.col.time'),
              dataIndex: 'time',
              render: (v: string) => fmtTime(v),
            },
            { title: t('pages.console.records.col.tank'), dataIndex: 'tank' },
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
    </>
  );
};

export default AlarmRecords;
