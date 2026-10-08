import { PlusOutlined } from '@ant-design/icons';
import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import { useAccess, useIntl } from '@umijs/max';
import { Button, message, Popconfirm, Space } from 'antd';
import type React from 'react';
import { useRef, useState } from 'react';
import { ApiError } from '@/services/ant-design-pro/apiError';
import { listCustomers } from '@/services/ant-design-pro/customers';
import {
  createStation,
  deleteStation,
  listStations,
  updateStation,
} from '@/services/ant-design-pro/stations';
import { describeDeleteStationError, describeStationError } from './errors';
import StationFormModal from './StationFormModal';

type Row = API.Station & { customerName?: string };

/**
 * 油站表格：列表（GET /api/stations）、新建、编辑、删除。平台管理员看到全部客户的油站，所以多一列"所属客户"，
 * 新建时必须选客户；客户管理员只有本客户的油站，新建不带 customerId（云端按其所属客户处理）。
 * 删除只允许没有设备的油站，否则云端 409，提示先把设备移走。
 */
const StationsTab: React.FC = () => {
  const intl = useIntl();
  const t = (id: string, values?: Record<string, string>) =>
    intl.formatMessage({ id }, values);
  const { canPlatformAdmin } = useAccess();
  const actionRef = useRef<ActionType>(undefined);
  const [customers, setCustomers] = useState<API.Customer[]>([]);
  const [createOpen, setCreateOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<Row>();
  const reload = () => actionRef.current?.reload();

  const remove = async (station: Row) => {
    try {
      await deleteStation(station.id);
      message.success(
        t('pages.console.stations.delete.success', { name: station.name }),
      );
      reload();
    } catch (err) {
      const apiErr = err instanceof ApiError ? err : undefined;
      if (apiErr?.authHandled) return;
      const m = describeDeleteStationError(
        apiErr?.status ?? -1,
        apiErr?.error ?? (err as Error)?.message,
      );
      message.error(t(m.id, { detail: m.detail ?? '' }));
    }
  };

  const columns: ProColumns<Row>[] = [
    { title: t('pages.console.stations.col.name'), dataIndex: 'name' },
    { title: t('pages.console.stations.col.address'), dataIndex: 'address' },
    ...(canPlatformAdmin
      ? [
          {
            title: t('pages.console.stations.col.customer'),
            dataIndex: 'customerName',
            render: (_: unknown, row: Row) =>
              row.customerName ?? `#${row.customerId}`,
          } as ProColumns<Row>,
        ]
      : []),
    {
      title: t('pages.console.stations.col.createdAt'),
      dataIndex: 'createdAt',
      valueType: 'dateTime',
    },
    {
      title: t('pages.console.stations.col.actions'),
      valueType: 'option',
      render: (_, row) => (
        <Space>
          <a onClick={() => setEditTarget(row)}>
            {t('pages.console.stations.action.edit')}
          </a>
          <Popconfirm
            title={t('pages.console.stations.delete.confirm', {
              name: row.name,
            })}
            okButtonProps={{ danger: true }}
            onConfirm={() => remove(row)}
          >
            <a>{t('pages.console.stations.action.delete')}</a>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <>
      <ProTable<Row>
        rowKey="id"
        actionRef={actionRef}
        columns={columns}
        search={false}
        options={{ density: false, setting: false }}
        pagination={{ pageSize: 20, showSizeChanger: false }}
        request={async () => {
          const stations = await listStations();
          if (!canPlatformAdmin) return { data: stations, success: true };
          // 客户名只是显示用，加载失败时退化成 #id，不拖垮油站列表
          const cs = await listCustomers().catch(() => [] as API.Customer[]);
          setCustomers(cs);
          const names = new Map(cs.map((c) => [c.id, c.name]));
          return {
            data: stations.map((s) => ({
              ...s,
              customerName: names.get(s.customerId),
            })),
            success: true,
          };
        }}
        // 失败提示已由全局请求错误处理弹出；这里不吞成空列表，只避免重复提示
        onRequestError={() => undefined}
        locale={{ emptyText: t('pages.console.stations.empty') }}
        toolBarRender={() => [
          <Button
            key="create"
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setCreateOpen(true)}
          >
            {t('pages.console.stations.create')}
          </Button>,
        ]}
      />
      <StationFormModal
        open={createOpen}
        title={t('pages.console.stations.create')}
        okText={t('pages.console.stations.form.submit.create')}
        customers={canPlatformAdmin ? customers : undefined}
        onSubmit={({ customerId, name, address }) =>
          createStation(
            canPlatformAdmin
              ? { customerId, name, address }
              : { name, address },
          )
        }
        describeError={describeStationError}
        successId="pages.console.stations.create.success"
        onClose={() => setCreateOpen(false)}
        onDone={reload}
      />
      <StationFormModal
        open={!!editTarget}
        title={`${t('pages.console.stations.action.edit')}：${editTarget?.name ?? ''}`}
        okText={t('pages.console.stations.form.submit.edit')}
        initial={
          editTarget && { name: editTarget.name, address: editTarget.address }
        }
        onSubmit={({ name, address }) =>
          updateStation((editTarget as Row).id, { name, address })
        }
        describeError={describeStationError}
        successId="pages.console.stations.edit.success"
        onClose={() => setEditTarget(undefined)}
        onDone={reload}
      />
    </>
  );
};

export default StationsTab;
