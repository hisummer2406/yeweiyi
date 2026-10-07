import { PlusOutlined } from '@ant-design/icons';
import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { useIntl, useModel } from '@umijs/max';
import { Badge, Button, message, Popconfirm, Space } from 'antd';
import type React from 'react';
import { useRef, useState } from 'react';
import { ApiError } from '@/services/ant-design-pro/apiError';
import { listCustomers } from '@/services/ant-design-pro/customers';
import { listUsers, updateUser } from '@/services/ant-design-pro/users';
import { describeUserError } from './errors';
import {
  CreateUserModal,
  MoveUserModal,
  ResetPasswordModal,
} from './UserModals';

type Row = API.AdminUser & { customerName?: string };

/**
 * 账号管理（仅 platform_admin，见 access.ts）：对接 GET/POST /api/users、PUT /api/users/:id
 * （停用/启用、重置密码）、PUT /api/users/:id/customer（换客户，会清掉该账号的设备绑定）。
 * 云端没有「改角色」接口，所以这里不提供；角色只能在创建时指定。
 */
const AdminUsers: React.FC = () => {
  const intl = useIntl();
  const t = (id: string) => intl.formatMessage({ id });
  const { initialState } = useModel('@@initialState');
  const selfId = initialState?.currentUser?.userId;
  const actionRef = useRef<ActionType>(undefined);
  const [customers, setCustomers] = useState<API.Customer[]>([]);
  const [createOpen, setCreateOpen] = useState(false);
  const [resetTarget, setResetTarget] = useState<API.AdminUser>();
  const [moveTarget, setMoveTarget] = useState<API.AdminUser>();
  const reload = () => actionRef.current?.reload();

  const toggleDisabled = async (user: API.AdminUser) => {
    try {
      await updateUser(user.id, { disabled: !user.disabled });
      message.success(
        t(
          user.disabled
            ? 'pages.ops.users.success.enabled'
            : 'pages.ops.users.success.disabled',
        ),
      );
      reload();
    } catch (err) {
      const apiErr = err instanceof ApiError ? err : undefined;
      if (apiErr?.authHandled) return;
      const m = describeUserError(
        apiErr?.status ?? -1,
        apiErr?.error ?? (err as Error)?.message,
      );
      message.error(
        intl.formatMessage({ id: m.id }, { detail: m.detail ?? '' }),
      );
    }
  };

  const columns: ProColumns<Row>[] = [
    { title: t('pages.ops.users.col.username'), dataIndex: 'username' },
    {
      title: t('pages.ops.users.col.role'),
      dataIndex: 'role',
      render: (_, row) => t(`pages.ops.users.role.${row.role}`),
    },
    {
      title: t('pages.ops.users.col.customer'),
      dataIndex: 'customerName',
      render: (_, row) =>
        row.customerId == null
          ? '-'
          : (row.customerName ?? `#${row.customerId}`),
    },
    {
      title: t('pages.ops.users.col.status'),
      dataIndex: 'disabled',
      render: (_, row) =>
        row.disabled ? (
          <Badge status="default" text={t('pages.ops.users.status.disabled')} />
        ) : (
          <Badge status="success" text={t('pages.ops.users.status.active')} />
        ),
    },
    {
      title: t('pages.ops.users.col.createdAt'),
      dataIndex: 'createdAt',
      valueType: 'dateTime',
    },
    {
      title: t('pages.ops.users.col.actions'),
      valueType: 'option',
      render: (_, row) => (
        <Space>
          <a onClick={() => setResetTarget(row)}>
            {t('pages.ops.users.action.resetPassword')}
          </a>
          {row.role !== 'platform_admin' && (
            <a onClick={() => setMoveTarget(row)}>
              {t('pages.ops.users.action.move')}
            </a>
          )}
          {row.id !== selfId &&
            (row.disabled ? (
              <a onClick={() => toggleDisabled(row)}>
                {t('pages.ops.users.action.enable')}
              </a>
            ) : (
              <Popconfirm
                title={t('pages.ops.users.disable.confirm')}
                onConfirm={() => toggleDisabled(row)}
              >
                <a>{t('pages.ops.users.action.disable')}</a>
              </Popconfirm>
            ))}
        </Space>
      ),
    },
  ];

  return (
    <PageContainer>
      <ProTable<Row>
        rowKey="id"
        actionRef={actionRef}
        columns={columns}
        search={false}
        options={{ density: false, setting: false }}
        pagination={{ pageSize: 20, showSizeChanger: false }}
        request={async () => {
          const [users, cs] = await Promise.all([
            listUsers(),
            // 客户名只是显示用，加载失败时退化成 #id，不拖垮账号列表
            listCustomers().catch(() => [] as API.Customer[]),
          ]);
          setCustomers(cs);
          const names = new Map(cs.map((c) => [c.id, c.name]));
          return {
            data: users.map((u) => ({
              ...u,
              customerName:
                u.customerId == null ? undefined : names.get(u.customerId),
            })),
            success: true,
          };
        }}
        // 失败提示已由全局请求错误处理弹出；这里不吞成空列表，只避免重复提示
        onRequestError={() => undefined}
        locale={{ emptyText: t('pages.ops.users.empty') }}
        toolBarRender={() => [
          <Button
            key="create"
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setCreateOpen(true)}
          >
            {t('pages.ops.users.create')}
          </Button>,
        ]}
      />
      <CreateUserModal
        open={createOpen}
        customers={customers}
        onClose={() => setCreateOpen(false)}
        onDone={reload}
      />
      <ResetPasswordModal
        open={!!resetTarget}
        user={resetTarget}
        onClose={() => setResetTarget(undefined)}
        onDone={reload}
      />
      <MoveUserModal
        open={!!moveTarget}
        user={moveTarget}
        customers={customers}
        onClose={() => setMoveTarget(undefined)}
        onDone={reload}
      />
    </PageContainer>
  );
};

export default AdminUsers;
