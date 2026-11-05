import { useIntl } from '@umijs/max';
import { Alert, Table, theme } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type React from 'react';
import { useEffect, useMemo, useState } from 'react';
import { filterRows, groupStripes, summarizeSynced } from '@/utils/records';
import RecordsFrame from './RecordsFrame';
import TankFilter from './TankFilter';
import { REFRESH_MS, useRecordsQuery } from './useRecordsQuery';
import { useTankSelection } from './useTankSelection';

type Props<R> = {
  device: string;
  fetcher: (q: API.RecordsQuery) => Promise<API.SyncedRecordList<R>>;
  columns: ColumnsType<R>;
  rowKey: (r: R) => string;
  /** 行所属的记录（交班 shiftId、库存 recordId）：同一记录的多行同底色，相邻两条交替 */
  groupKey: (r: R) => string;
  emptyId: string;
};

/**
 * 交班、库存记录共用（cloud#85）：设备 + 罐号筛选 + 时间窗，最新在前、每页 20 条由云端分页。
 * syncStatus / unparsable / truncated 由 summarizeSynced 转成提示：UNSUPPORTED、SYNCING、未知状态时
 * 空列表不显示成「没有记录」。罐号由云端先筛选后分页（#84），按行再过滤一遍只为兜底。
 */
function SyncedRecords<R extends { tank: number }>({
  device,
  fetcher,
  columns,
  rowKey,
  groupKey,
  emptyId,
}: Props<R>) {
  const intl = useIntl();
  const { token } = theme.useToken();
  const [error, setError] = useState<unknown>();
  const { tank, refused, setTank } = useTankSelection(device, error);
  const state = useRecordsQuery(fetcher, device, REFRESH_MS, { tank });
  useEffect(() => setError(state.error), [state.error]);
  const rows = useMemo(
    () => filterRows(state.data?.rows ?? [], { tank }),
    [state.data, tank],
  );
  const notices = useMemo(
    () => (state.data ? summarizeSynced(state.data) : []),
    [state.data],
  );
  // 底色按整份列表算好再按行键取：分页后 onRow 的下标是页内下标，不能直接用
  const stripes = useMemo(() => {
    const s = groupStripes(rows, groupKey);
    return new Map(rows.map((r, i) => [rowKey(r), s[i]]));
  }, [rows, rowKey, groupKey]);

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
      emptyId={emptyId}
    >
      <Table<R>
        size="middle"
        rowKey={rowKey}
        dataSource={rows}
        pagination={false}
        loading={state.refreshing && !state.loading}
        scroll={{ x: 'max-content' }}
        onRow={(r) => ({
          'data-stripe': stripes.get(rowKey(r)),
          style:
            stripes.get(rowKey(r)) === 1
              ? { background: token.colorFillQuaternary }
              : undefined,
        })}
        columns={columns}
      />
    </RecordsFrame>
  );
}

export default SyncedRecords;
