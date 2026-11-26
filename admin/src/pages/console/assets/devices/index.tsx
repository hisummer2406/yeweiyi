import { PlusOutlined } from '@ant-design/icons';
import type { ProColumns } from '@ant-design/pro-components';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { useAccess, useIntl } from '@umijs/max';
import { Badge, Button, message, Popconfirm, Space } from 'antd';
import type React from 'react';
import { useEffect, useRef, useState } from 'react';
import { KeywordSearchBox, useKeywordSearch } from '@/components/KeywordSearch';
import { ApiError } from '@/services/ant-design-pro/apiError';
import { listCustomers } from '@/services/ant-design-pro/customers';
import { listDevices } from '@/services/ant-design-pro/devices';
import {
  assignDeviceStation,
  listStations,
} from '@/services/ant-design-pro/stations';
import { filterByKeyword, matchDevice } from '@/utils/listSearch';
import { describeAssignStationError } from '../stations/errors';
import AssignStationModal from './AssignStationModal';
import { downloadMiniCode } from './downloadMiniCode';
import RegisterDeviceModal from './RegisterDeviceModal';
import ReplaceDeviceModal from './ReplaceDeviceModal';
import TransferDeviceModal from './TransferDeviceModal';

/**
 * 资产管理 · 设备（/assets/devices）：列出调用者可见的设备（GET /api/devices，平台管理员看到全部，
 * 客户管理员看到本客户的）。设备的登记、客户归属、油站归属都在这一页：
 * - 平台管理员：「登记设备」（POST /api/devices，见 RegisterDeviceModal）、行内「分配/转移客户」
 *   （PUT /api/devices/:device/customer）、「所属客户」列。客户归属来自行的 customer_id（云端只给平台管理员）；
 *   字段缺失（云端旧版本）显示「未知」，不当作未分配。
 * - 平台管理员与客户管理员：行内「分配油站」（PUT /api/devices/:device/station）。没有客户的设备（customer_id 为 null）
 *   不提供，云端不允许；客户管理员的行没有 customer_id 字段，照常提供，由云端裁决。
 * - 平台管理员与客户管理员：行内「解除油站关联」（同一个 PUT，stationId: null；#69）：设备回到「未分组」，可再分配。
 *   设备档案、历史、ThingsBoard 实体与接入凭据都保留，终端照常上报。日常移除设备就用它；彻底删除已从网页端关闭，
 *   云端对旧接口返回 410 device_purge_disabled。
 * 登记/转移只是 UI 上按角色隐藏，真正的权限边界在云端（客户管理员调用会被 403）。
 */
type Row = API.DeviceRow & { customerName?: string; stationName?: string };

const AdminDevices: React.FC = () => {
  const intl = useIntl();
  const t = (id: string, values?: Record<string, string>) =>
    intl.formatMessage({ id }, values);
  const { canPlatformAdmin } = useAccess();
  const [rows, setRows] = useState<Row[]>([]);
  const [loading, setLoading] = useState(false);
  const search = useKeywordSearch();
  const [registerOpen, setRegisterOpen] = useState(false);
  const [customers, setCustomers] = useState<API.Customer[]>([]);
  const [transferTarget, setTransferTarget] = useState<API.DeviceRow>();
  const [stations, setStations] = useState<API.Station[]>([]);
  const [stationTarget, setStationTarget] = useState<API.DeviceRow>();
  const [replaceTarget, setReplaceTarget] = useState<API.DeviceRow>();
  // 最近一次加载的行：登记成功提示里的「分配油站」用它取到设备当前的油站（已登记的设备可能已有油站）
  const rowsRef = useRef<API.DeviceRow[]>([]);

  const fetchRows = async (): Promise<Row[]> => {
    const [deviceRows, cs, ss] = await Promise.all([
      listDevices(),
      // 客户名只是显示用，加载失败时退化成 #id，不拖垮设备列表；
      // 云端只允许平台管理员读客户列表，客户管理员不请求（否则必然 403 并弹全局错误）
      canPlatformAdmin
        ? listCustomers().catch(() => [] as API.Customer[])
        : Promise.resolve([] as API.Customer[]),
      listStations({ silent: true }).catch(() => [] as API.Station[]),
    ]);
    rowsRef.current = deviceRows;
    setCustomers(cs);
    setStations(ss);
    const stationNames = new Map(ss.map((x) => [x.id, x.name]));
    const names = new Map(cs.map((c) => [c.id, c.name]));
    return deviceRows.map((r) => ({
      ...r,
      customerName:
        r.customer_id == null ? undefined : names.get(r.customer_id),
      stationName:
        r.station_id == null ? undefined : stationNames.get(r.station_id),
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

  // 解除油站关联（#69）：只清 station_id，设备档案、历史、ThingsBoard 实体与凭据都不动。
  // 无论成败都刷新：400/404/409 说明页面已过期，刷新后让用户看到真实状态
  const unassign = async (row: Row) => {
    try {
      // changed=false 是别人刚解除过，结果相同，同样提示已移出
      await assignDeviceStation(row.device_name, null);
      message.success(
        t('pages.console.stations.assign.cleared', { device: row.device_name }),
      );
    } catch (err) {
      const apiErr = err instanceof ApiError ? err : undefined;
      if (apiErr?.authHandled) return; // 鉴权失败，已在回登录页
      const m = describeAssignStationError(
        apiErr?.status ?? -1,
        apiErr?.error ?? (err as Error)?.message,
        apiErr?.code,
        apiErr?.occupiedBy,
      );
      message.error(t(m.id, { detail: m.detail ?? '' }));
    }
    reload();
  };

  const customerColumn: ProColumns<Row> = {
    title: t('pages.ops.devices.col.customer'),
    dataIndex: 'customerName',
    render: (_, row) => {
      if (row.customer_id === undefined)
        return t('pages.ops.devices.customer.unknown');
      if (row.customer_id === null)
        return t('pages.ops.devices.customer.unassigned');
      return row.customerName ?? `#${row.customer_id}`;
    },
  };

  const columns: ProColumns<Row>[] = [
    { title: t('pages.ops.devices.col.name'), dataIndex: 'device_name' },
    ...(canPlatformAdmin ? [customerColumn] : []),
    {
      title: t('pages.console.stations.devices.col.station'),
      dataIndex: 'stationName',
      render: (_, row) => {
        if (row.station_id === undefined)
          return t('pages.console.stations.devices.station.unknown');
        if (row.station_id === null)
          return t('pages.console.stations.devices.station.none');
        return row.stationName ?? `#${row.station_id}`;
      },
    },
    {
      title: t('pages.ops.devices.col.status'),
      dataIndex: 'is_online',
      render: (_, row) => {
        if (row.is_online)
          return (
            <Badge
              status="success"
              text={t('pages.ops.devices.status.online')}
            />
          );
        // 刚登记、还没上报过的设备不是「掉线」，别和曾经上线后离线的混在一起（#16 G3）
        const never = row.last_online_at === null;
        return (
          <Badge
            status="default"
            text={t(
              never
                ? 'pages.ops.devices.status.neverOnline'
                : 'pages.ops.devices.status.offline',
            )}
          />
        );
      },
    },
    {
      title: t('pages.ops.devices.col.lastOnline'),
      dataIndex: 'last_online_at',
      valueType: 'dateTime',
    },
    {
      title: t('pages.ops.devices.col.lastOffline'),
      dataIndex: 'last_offline_at',
      valueType: 'dateTime',
    },
    {
      title: t('pages.ops.devices.col.updatedAt'),
      dataIndex: 'updated_at',
      valueType: 'dateTime',
    },
    {
      title: t('pages.ops.devices.col.actions'),
      valueType: 'option',
      render: (_, row) => (
        <Space>
          {canPlatformAdmin && (
            <a onClick={() => setTransferTarget(row)}>
              {t(
                row.customer_id === null
                  ? 'pages.ops.devices.action.assign'
                  : 'pages.ops.devices.action.transfer',
              )}
            </a>
          )}
          {row.customer_id !== null && (
            <a onClick={() => setStationTarget(row)}>
              {t('pages.console.stations.devices.action.assign')}
            </a>
          )}
          {canPlatformAdmin && (
            <a onClick={() => downloadMiniCode(row.device_name, t)}>
              {t('pages.console.miniCode.action')}
            </a>
          )}
          {typeof row.station_id === 'number' && (
            <a onClick={() => setReplaceTarget(row)}>
              {t('pages.console.stations.devices.action.replace')}
            </a>
          )}
          {typeof row.station_id === 'number' && (
            <Popconfirm
              title={t('pages.ops.devices.unassign.title', {
                device: row.device_name,
              })}
              description={t('pages.ops.devices.unassign.description')}
              okText={t('pages.ops.devices.unassign.ok')}
              cancelText={t('pages.console.stations.form.cancel')}
              onConfirm={() => unassign(row)}
            >
              <a>{t('pages.ops.devices.unassign.action')}</a>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <PageContainer>
      <ProTable<Row>
        rowKey="device_name"
        columns={columns}
        search={false}
        loading={loading}
        dataSource={filterByKeyword(rows, search.keyword, matchDevice)}
        options={{ density: false, setting: false, reload }}
        pagination={search.pagination}
        locale={{
          emptyText: t(
            search.searching && rows.length > 0
              ? 'pages.assets.search.empty'
              : 'pages.ops.devices.empty',
          ),
        }}
        headerTitle={
          <KeywordSearchBox
            placeholder={t(
              canPlatformAdmin
                ? 'pages.ops.devices.search.platform'
                : 'pages.ops.devices.search',
            )}
            onSearch={search.onSearch}
          />
        }
        toolBarRender={() =>
          canPlatformAdmin
            ? [
                <Button
                  key="register"
                  type="primary"
                  icon={<PlusOutlined />}
                  onClick={() => setRegisterOpen(true)}
                >
                  {t('pages.ops.devices.register')}
                </Button>,
              ]
            : []
        }
      />
      <RegisterDeviceModal
        open={registerOpen}
        onClose={() => setRegisterOpen(false)}
        onRegistered={() => reload()}
        onAssignStation={(o) =>
          // 登记成功提示里的「分配油站」：目标是刚登记的设备与其所属客户，候选为该客户的油站
          setStationTarget(
            rowsRef.current.find((r) => r.device_name === o.deviceName) ?? {
              device_name: o.deviceName,
              customer_id: o.customerId,
              is_online: false,
              last_online_at: null,
              last_offline_at: null,
              updated_at: null,
            },
          )
        }
      />
      <TransferDeviceModal
        device={transferTarget}
        customers={customers}
        onClose={() => setTransferTarget(undefined)}
        onDone={() => reload()}
      />
      <ReplaceDeviceModal
        device={replaceTarget}
        devices={rowsRef.current}
        stations={stations}
        onClose={() => setReplaceTarget(undefined)}
        onDone={() => reload()}
      />
      <AssignStationModal
        device={stationTarget}
        stations={stations}
        devices={rowsRef.current}
        onClose={() => setStationTarget(undefined)}
        onDone={() => reload()}
      />
    </PageContainer>
  );
};

export default AdminDevices;
