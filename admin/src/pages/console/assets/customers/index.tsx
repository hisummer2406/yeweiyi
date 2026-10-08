import { PlusOutlined } from '@ant-design/icons';
import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import { Button, message, Popconfirm, Space } from 'antd';
import type React from 'react';
import { useRef, useState } from 'react';
import { ApiError } from '@/services/ant-design-pro/apiError';
import {
  createCustomer,
  deleteCustomer,
  listCustomers,
  renameCustomer,
} from '@/services/ant-design-pro/customers';
import { describeCustomerError } from '../devices/errors';
import CustomerNameModal from './CustomerNameModal';
import {
  describeDeleteError,
  describeRenameError,
  isDefaultCustomer,
} from './errors';

/**
 * 客户管理：列表（GET /api/customers）、新建（POST）、重命名（PUT /api/customers/:id）、删除（DELETE）。
 * 删除是真删除、不可恢复，云端只允许删没有账号、设备、历史记录的客户（否则 409）；名为 default 的
 * 内置客户不能改名也不能删，所以不给它这两个操作。设备登记页的内联新建客户走同一个创建接口。
 */
const AdminCustomers: React.FC = () => {
  const intl = useIntl();
  const t = (id: string, values?: Record<string, string>) =>
    intl.formatMessage({ id }, values);
  const actionRef = useRef<ActionType>(undefined);
  const [createOpen, setCreateOpen] = useState(false);
  const [renameTarget, setRenameTarget] = useState<API.Customer>();
  const reload = () => actionRef.current?.reload();

  const remove = async (customer: API.Customer) => {
    try {
      await deleteCustomer(customer.id);
      message.success(
        t('pages.ops.customers.delete.success', { name: customer.name }),
      );
      reload();
    } catch (err) {
      const apiErr = err instanceof ApiError ? err : undefined;
      if (apiErr?.authHandled) return; // 鉴权失败，已在回登录页
      const m = describeDeleteError(
        apiErr?.status ?? -1,
        apiErr?.error ?? (err as Error)?.message,
      );
      message.error(t(m.id, { detail: m.detail ?? '' }));
    }
  };

  const columns: ProColumns<API.Customer>[] = [
    { title: t('pages.ops.customers.col.id'), dataIndex: 'id' },
    { title: t('pages.ops.customers.col.name'), dataIndex: 'name' },
    {
      title: t('pages.ops.customers.col.createdAt'),
      dataIndex: 'createdAt',
      valueType: 'dateTime',
    },
    {
      title: t('pages.ops.customers.col.actions'),
      valueType: 'option',
      render: (_, row) =>
        isDefaultCustomer(row) ? null : (
          <Space>
            <a onClick={() => setRenameTarget(row)}>
              {t('pages.ops.customers.action.rename')}
            </a>
            <Popconfirm
              title={t('pages.ops.customers.delete.confirm', {
                name: row.name,
              })}
              okButtonProps={{ danger: true }}
              onConfirm={() => remove(row)}
            >
              <a>{t('pages.ops.customers.action.delete')}</a>
            </Popconfirm>
          </Space>
        ),
    },
  ];

  return (
    <PageContainer>
      <ProTable<API.Customer>
        rowKey="id"
        actionRef={actionRef}
        columns={columns}
        search={false}
        options={{ density: false, setting: false }}
        pagination={{ pageSize: 20, showSizeChanger: false }}
        request={async () => ({ data: await listCustomers(), success: true })}
        // 失败提示已由全局请求错误处理弹出；这里不吞成空列表，只避免重复提示
        onRequestError={() => undefined}
        locale={{ emptyText: t('pages.ops.customers.empty') }}
        toolBarRender={() => [
          <Button
            key="create"
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setCreateOpen(true)}
          >
            {t('pages.ops.customers.create')}
          </Button>,
        ]}
      />
      <CustomerNameModal
        open={createOpen}
        title={t('pages.ops.customers.create')}
        okText={t('pages.ops.customers.form.submit')}
        onSubmit={(name) => createCustomer({ name })}
        describeError={describeCustomerError}
        successId="pages.ops.devices.customer.created"
        onClose={() => setCreateOpen(false)}
        onDone={reload}
      />
      <CustomerNameModal
        open={!!renameTarget}
        title={`${t('pages.ops.customers.action.rename')}：${renameTarget?.name ?? ''}`}
        okText={t('pages.ops.customers.form.submit.rename')}
        initialName={renameTarget?.name}
        onSubmit={(name) =>
          renameCustomer((renameTarget as API.Customer).id, name)
        }
        describeError={describeRenameError}
        successId="pages.ops.customers.rename.success"
        onClose={() => setRenameTarget(undefined)}
        onDone={reload}
      />
    </PageContainer>
  );
};

export default AdminCustomers;
