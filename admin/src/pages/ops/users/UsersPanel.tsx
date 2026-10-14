import { PlusOutlined } from '@ant-design/icons';
import type { ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import { history, useIntl, useModel } from '@umijs/max';
import { Badge, Button, Input, message, Popconfirm, Select, Space } from 'antd';
import type React from 'react';
import { useEffect, useState } from 'react';
import { ApiError } from '@/services/ant-design-pro/apiError';
import { listCustomers } from '@/services/ant-design-pro/customers';
import { listUsers, updateUser } from '@/services/ant-design-pro/users';
import {
  filterUsers,
  parseUserFilter,
  serializeUserFilter,
  type UserFilter,
} from '@/utils/userFilter';
import { describeUserError, userLabel } from './errors';
import {
  CreateUserModal,
  MoveUserModal,
  ResetPasswordModal,
} from './UserModals';

type Row = API.AdminUser & { customerName?: string };

type Props = {
  /** 锁定到某个客户：只列该客户的账号，不能换客户筛选，新建自动带入该客户，也不读写 URL 参数 */
  customerId?: number;
  /** 只管平台管理员账号（右上角账号菜单入口）：只列、只建平台管理员，不出现客户相关的列和筛选 */
  platformOnly?: boolean;
};

/**
 * 账号列表面板，被「运维管理 → 账号管理」（全局）和「资产管理 → 客户 → 账号」（锁定客户）共用。
 * 仅 platform_admin（见 access.ts）：对接 GET/POST /api/users、PUT /api/users/:id
 * （停用/启用、重置密码）、PUT /api/users/:id/customer（换客户，会清掉该账号的设备绑定）。
 * 云端没有「改角色」接口，所以这里不提供；角色只能在创建时指定。
 */
const UsersPanel: React.FC<Props> = ({
  customerId: lockedCustomerId,
  platformOnly = false,
}) => {
  const intl = useIntl();
  const t = (id: string) => intl.formatMessage({ id });
  const { initialState } = useModel('@@initialState');
  const selfId = initialState?.currentUser?.userId;
  const [customers, setCustomers] = useState<API.Customer[]>([]);
  const [createOpen, setCreateOpen] = useState(false);
  const [resetTarget, setResetTarget] = useState<API.AdminUser>();
  const [moveTarget, setMoveTarget] = useState<API.AdminUser>();
  const [rows, setRows] = useState<Row[]>([]);
  const [loading, setLoading] = useState(false);
  const locked = lockedCustomerId != null;
  const [filter, setFilter] = useState<UserFilter>(() =>
    locked
      ? { keyword: '', customerId: lockedCustomerId }
      : parseUserFilter(window.location.search),
  );
  // 平台管理员账号与客户侧账号分开管：前者在右上角账号菜单，后者在账号管理
  const scoped = rows.filter((u) =>
    platformOnly ? u.role === 'platform_admin' : u.role !== 'platform_admin',
  );
  // 管理员和员工平铺在同一张表里（员工不藏在管理员行下面）：按客户分组，同一客户里管理员在前
  const rank = (u: Row) => (u.role === 'customer_user' ? 1 : 0);
  const visible = filterUsers(scoped, filter).sort(
    (x, y) =>
      (x.customerName ?? '').localeCompare(y.customerName ?? '') ||
      (x.customerId ?? 0) - (y.customerId ?? 0) ||
      rank(x) - rank(y) ||
      x.id - y.id,
  );
  // 一客户一管理员：已有管理员的客户，不允许再换入第二个
  const adminCustomerIds = rows
    .filter((u) => u.role === 'customer_admin' && u.customerId != null)
    .map((u) => u.customerId as number);
  const updateFilter = (patch: Partial<UserFilter>) => {
    const next = { ...filter, ...patch };
    if (locked) next.customerId = lockedCustomerId;
    setFilter(next);
    if (!locked) {
      history.replace(
        `${window.location.pathname}${serializeUserFilter(next)}`,
      );
    }
  };
  // 一次取全后在前端过滤；失败提示已由全局请求错误处理弹出，这里只保留旧列表
  const load = async () => {
    setLoading(true);
    try {
      const [users, cs] = await Promise.all([
        listUsers(),
        // 客户名只是显示用，加载失败时退化成 #id，不拖垮账号列表
        listCustomers().catch(() => [] as API.Customer[]),
      ]);
      setCustomers(cs);
      const names = new Map(cs.map((c) => [c.id, c.name]));
      setRows(
        users.map((u) => ({
          ...u,
          customerName:
            u.customerId == null ? undefined : names.get(u.customerId),
        })),
      );
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
        apiErr?.code,
      );
      message.error(
        intl.formatMessage({ id: m.id }, { detail: m.detail ?? '' }),
      );
    }
  };

  const allColumns: ProColumns<Row>[] = [
    {
      title: t('pages.ops.users.col.account'),
      dataIndex: 'phone',
      render: (_, row) => userLabel(row),
    },
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
          {/* 员工没有密码可重置（云端对员工重置密码返回 409） */}
          {row.role !== 'customer_user' && (
            <a onClick={() => setResetTarget(row)}>
              {t('pages.ops.users.action.resetPassword')}
            </a>
          )}
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

  // 平台管理员没有客户，角色也只有一种：这两列在 platformOnly 下没有信息量
  const columns = platformOnly
    ? allColumns.filter(
        (c) => c.dataIndex !== 'customerName' && c.dataIndex !== 'role',
      )
    : allColumns;

  return (
    <>
      <Space wrap style={{ marginBottom: 16 }}>
        <Input.Search
          allowClear
          style={{ width: 240 }}
          placeholder={t('pages.ops.users.filter.keyword')}
          defaultValue={filter.keyword}
          onSearch={(v) => updateFilter({ keyword: v })}
        />
        {!locked && !platformOnly && (
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            style={{ width: 200 }}
            placeholder={t('pages.ops.users.filter.customer')}
            value={filter.customerId}
            onChange={(v) => updateFilter({ customerId: v ?? undefined })}
            options={customers.map((c) => ({ value: c.id, label: c.name }))}
          />
        )}
        {!platformOnly && (
          <Select
            allowClear
            style={{ width: 140 }}
            placeholder={t('pages.ops.users.col.role')}
            value={filter.role}
            onChange={(v) => updateFilter({ role: v ?? undefined })}
            options={(['customer_admin', 'customer_user'] as const).map(
              (r) => ({ value: r, label: t(`pages.ops.users.role.${r}`) }),
            )}
          />
        )}
        <Select
          allowClear
          style={{ width: 120 }}
          placeholder={t('pages.ops.users.col.status')}
          value={filter.status}
          onChange={(v) => updateFilter({ status: v ?? undefined })}
          options={[
            { value: 'active', label: t('pages.ops.users.status.active') },
            { value: 'disabled', label: t('pages.ops.users.status.disabled') },
          ]}
        />
      </Space>
      <ProTable<Row>
        rowKey="id"
        columns={columns}
        search={false}
        loading={loading}
        dataSource={visible}
        options={{ density: false, setting: false, reload: load }}
        pagination={{ pageSize: 20, showSizeChanger: false }}
        locale={{
          emptyText: t(
            scoped.length > 0
              ? 'pages.ops.users.empty.filtered'
              : 'pages.ops.users.empty',
          ),
        }}
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
        defaultCustomerId={filter.customerId}
        allowedRoles={platformOnly ? ['platform_admin'] : ['customer_user']}
        onClose={() => setCreateOpen(false)}
        onDone={(created) => {
          // 员工建好后直接去授权：没有授权的员工什么都看不到，授权页会自动打开该员工的分配弹窗
          if (created?.role === 'customer_user') {
            history.push(`/assets/staff?user=${created.id}`);
          } else {
            reload();
          }
        }}
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
        adminCustomerIds={adminCustomerIds}
        onClose={() => setMoveTarget(undefined)}
        onDone={reload}
      />
    </>
  );
};

export default UsersPanel;
