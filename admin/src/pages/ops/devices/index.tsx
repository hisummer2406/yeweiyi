import { PlusOutlined } from '@ant-design/icons';
import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import { Badge, Button } from 'antd';
import type React from 'react';
import { useRef, useState } from 'react';
import { listCustomers } from '@/services/ant-design-pro/customers';
import { listDevices } from '@/services/ant-design-pro/devices';
import RegisterDeviceModal from './RegisterDeviceModal';
import TransferDeviceModal from './TransferDeviceModal';

/**
 * 设备管理：列出业务库里已登记的设备（GET /api/devices，平台管理员看到全部），
 * 并提供「登记设备」（POST /api/devices，见 RegisterDeviceModal）。
 * 客户归属来自列表行的 customer_id（云端只给平台管理员）；字段缺失（云端旧版本）显示「未知」，
 * 不当作未分配。行内「转移客户」走 PUT /api/devices/:device/customer。
 */
type Row = API.DeviceRow & { customerName?: string };

const AdminDevices: React.FC = () => {
  const intl = useIntl();
  const t = (id: string) => intl.formatMessage({ id });
  const actionRef = useRef<ActionType>(undefined);
  const [registerOpen, setRegisterOpen] = useState(false);
  const [customers, setCustomers] = useState<API.Customer[]>([]);
  const [transferTarget, setTransferTarget] = useState<API.DeviceRow>();

  const columns: ProColumns<Row>[] = [
    { title: t('pages.ops.devices.col.name'), dataIndex: 'device_name' },
    {
      title: t('pages.ops.devices.col.customer'),
      dataIndex: 'customerName',
      render: (_, row) => {
        if (row.customer_id === undefined)
          return t('pages.ops.devices.customer.unknown');
        if (row.customer_id === null)
          return t('pages.ops.devices.customer.unassigned');
        return row.customerName ?? `#${row.customer_id}`;
      },
    },
    {
      title: t('pages.ops.devices.col.status'),
      dataIndex: 'is_online',
      render: (_, row) =>
        row.is_online ? (
          <Badge status="success" text={t('pages.ops.devices.status.online')} />
        ) : (
          <Badge
            status="default"
            text={t('pages.ops.devices.status.offline')}
          />
        ),
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
        <a onClick={() => setTransferTarget(row)}>
          {t(
            row.customer_id === null
              ? 'pages.ops.devices.action.assign'
              : 'pages.ops.devices.action.transfer',
          )}
        </a>
      ),
    },
  ];

  return (
    <PageContainer>
      <ProTable<Row>
        rowKey="device_name"
        actionRef={actionRef}
        columns={columns}
        search={false}
        options={{ density: false, setting: false }}
        pagination={{ pageSize: 20, showSizeChanger: false }}
        request={async () => {
          const [rows, cs] = await Promise.all([
            listDevices(),
            // 客户名只是显示用，加载失败时退化成 #id，不拖垮设备列表
            listCustomers().catch(() => [] as API.Customer[]),
          ]);
          setCustomers(cs);
          const names = new Map(cs.map((c) => [c.id, c.name]));
          return {
            data: rows.map((r) => ({
              ...r,
              customerName:
                r.customer_id == null ? undefined : names.get(r.customer_id),
            })),
            success: true,
          };
        }}
        // 失败提示已由全局请求错误处理弹出；这里不吞成空列表，只避免重复提示
        onRequestError={() => undefined}
        locale={{ emptyText: t('pages.ops.devices.empty') }}
        toolBarRender={() => [
          <Button
            key="register"
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setRegisterOpen(true)}
          >
            {t('pages.ops.devices.register')}
          </Button>,
        ]}
      />
      <RegisterDeviceModal
        open={registerOpen}
        onClose={() => setRegisterOpen(false)}
        onRegistered={() => actionRef.current?.reload()}
      />
      <TransferDeviceModal
        device={transferTarget}
        customers={customers}
        onClose={() => setTransferTarget(undefined)}
        onDone={() => actionRef.current?.reload()}
      />
    </PageContainer>
  );
};

export default AdminDevices;
