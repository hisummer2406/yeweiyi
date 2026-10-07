import { PlusOutlined } from '@ant-design/icons';
import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import { Badge, Button } from 'antd';
import type React from 'react';
import { useRef, useState } from 'react';
import { listDevices } from '@/services/ant-design-pro/devices';
import RegisterDeviceModal from './RegisterDeviceModal';

/**
 * 设备管理：列出业务库里已登记的设备（GET /api/devices，平台管理员看到全部），
 * 并提供「登记设备」（POST /api/devices，见 RegisterDeviceModal）。
 * 列表行没有客户归属字段，所以这里不显示客户；登记成功的提示里会带上所选客户。
 */
const AdminDevices: React.FC = () => {
  const intl = useIntl();
  const t = (id: string) => intl.formatMessage({ id });
  const actionRef = useRef<ActionType>(undefined);
  const [registerOpen, setRegisterOpen] = useState(false);

  const columns: ProColumns<API.DeviceRow>[] = [
    { title: t('pages.ops.devices.col.name'), dataIndex: 'device_name' },
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
  ];

  return (
    <PageContainer>
      <ProTable<API.DeviceRow>
        rowKey="device_name"
        actionRef={actionRef}
        columns={columns}
        search={false}
        options={{ density: false, setting: false }}
        pagination={{ pageSize: 20, showSizeChanger: false }}
        request={async () => ({ data: await listDevices(), success: true })}
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
    </PageContainer>
  );
};

export default AdminDevices;
