import { PlusOutlined } from '@ant-design/icons';
import type { ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import { useAccess, useIntl } from '@umijs/max';
import { Button, message, Popconfirm, Space, Tag, Tooltip } from 'antd';
import type React from 'react';
import { useEffect, useState } from 'react';
import { KeywordSearchBox, useKeywordSearch } from '@/components/KeywordSearch';
import { ApiError } from '@/services/ant-design-pro/apiError';
import { listCustomers } from '@/services/ant-design-pro/customers';
import { listDevices } from '@/services/ant-design-pro/devices';
import {
  createStation,
  deleteStation,
  listStations,
  updateStation,
} from '@/services/ant-design-pro/stations';
import { filterByKeyword, matchStation } from '@/utils/listSearch';
import { occupantsByStation } from '@/utils/stationOccupancy';
import { downloadMiniCode } from '../devices/downloadMiniCode';
import { describeDeleteStationError, describeStationError } from './errors';
import StationFormModal from './StationFormModal';

/**
 * devices：当前挂在该油站名下的设备编号。undefined = 设备列表没取到（或旧版云端不带 station_id），
 * 显示「未知」而不是「未安装」。一个油站只应有一台大屏，多于一台是历史冲突数据，如实标出，不挑一个顶替。
 */
type Row = API.Station & { customerName?: string; devices?: string[] };

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
  const [customers, setCustomers] = useState<API.Customer[]>([]);
  const [createOpen, setCreateOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<Row>();
  const [rows, setRows] = useState<Row[]>([]);
  const [loading, setLoading] = useState(false);
  const search = useKeywordSearch();

  const fetchRows = async (): Promise<Row[]> => {
    const [stations, deviceRows] = await Promise.all([
      listStations(),
      // 当前大屏只是显示用，设备列表失败不拖垮油站列表，退化为「未知」
      listDevices().catch(() => undefined),
    ]);
    // 旧版云端的设备行没有 station_id，推不出占用关系，同样退化为「未知」
    const occupants = deviceRows?.some((d) => d.station_id !== undefined)
      ? occupantsByStation(deviceRows)
      : undefined;
    const withDevices = stations.map((s) => ({
      ...s,
      devices: occupants ? (occupants.get(s.id) ?? []) : undefined,
    }));
    if (!canPlatformAdmin) return withDevices;
    // 客户名只是显示用，加载失败时退化成 #id，不拖垮油站列表
    const cs = await listCustomers().catch(() => [] as API.Customer[]);
    setCustomers(cs);
    const names = new Map(cs.map((c) => [c.id, c.name]));
    return withDevices.map((s) => ({
      ...s,
      customerName: names.get(s.customerId),
    }));
  };
  // 一次取全后在前端搜索、分页（#108）；失败提示已由全局请求错误处理弹出，这里只保留旧列表
  const load = async () => {
    setLoading(true);
    try {
      setRows(await fetchRows());
    } catch {
      // 已由全局处理
    } finally {
      setLoading(false);
    }
  };
  const reload = () => {
    void load();
  };
  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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
    {
      title: t('pages.console.stations.col.device'),
      dataIndex: 'devices',
      render: (_: unknown, row: Row) => {
        if (row.devices === undefined)
          return t('pages.console.stations.device.unknown');
        if (row.devices.length === 0)
          return t('pages.console.stations.device.none');
        if (row.devices.length === 1) return row.devices[0];
        return (
          <Tooltip title={row.devices.join('、')}>
            <Tag color="error">
              {t('pages.console.stations.device.conflict', {
                count: String(row.devices.length),
              })}
            </Tag>
          </Tooltip>
        );
      },
    },
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
          {row.devices?.length === 1 && (
            <a
              href={`/console/devices/${encodeURIComponent(row.devices[0])}?station=${row.id}`}
            >
              {t('pages.console.stations.action.monitor')}
            </a>
          )}
          {canPlatformAdmin && row.devices?.length === 1 && (
            <a onClick={() => downloadMiniCode(row.devices?.[0] ?? '', t)}>
              {t('pages.console.miniCode.action')}
            </a>
          )}
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
        columns={columns}
        search={false}
        loading={loading}
        dataSource={filterByKeyword(rows, search.keyword, matchStation)}
        options={{ density: false, setting: false, reload }}
        pagination={search.pagination}
        locale={{
          emptyText: t(
            search.searching && rows.length > 0
              ? 'pages.assets.search.empty'
              : 'pages.console.stations.empty',
          ),
        }}
        headerTitle={
          <KeywordSearchBox
            placeholder={t(
              canPlatformAdmin
                ? 'pages.console.stations.search.platform'
                : 'pages.console.stations.search',
            )}
            onSearch={search.onSearch}
          />
        }
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
