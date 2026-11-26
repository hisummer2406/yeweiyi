import { PlusOutlined } from '@ant-design/icons';
import type { ProColumns } from '@ant-design/pro-components';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { history, useIntl } from '@umijs/max';
import { Button, message, Popconfirm, Space, Tag } from 'antd';
import type React from 'react';
import { useEffect, useState } from 'react';
import { KeywordSearchBox, useKeywordSearch } from '@/components/KeywordSearch';
import { ApiError } from '@/services/ant-design-pro/apiError';
import {
  deleteCustomer,
  listCustomers,
  renameCustomer,
} from '@/services/ant-design-pro/customers';
import { filterByKeyword, matchCustomer } from '@/utils/listSearch';
import BrandLogoModal from './BrandLogoModal';
import CreateCustomerModal from './CreateCustomerModal';
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
  const [rows, setRows] = useState<API.Customer[]>([]);
  const [loading, setLoading] = useState(false);
  const search = useKeywordSearch();
  const [createOpen, setCreateOpen] = useState(false);
  const [renameTarget, setRenameTarget] = useState<API.Customer>();
  const [brandTarget, setBrandTarget] = useState<API.Customer>();
  // 新建成功后直接进入该客户的开通向导（新客户一定是配置中，cloud#60）
  const openOnboarding = (id: number) =>
    history.push(`/assets/customers/${id}/onboarding`);
  // 一次取全后在前端搜索、分页；失败提示已由全局请求错误处理弹出，这里只保留旧列表
  const load = async () => {
    setLoading(true);
    try {
      setRows(await listCustomers());
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
  }, []);

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
      title: t('pages.ops.customers.col.status'),
      dataIndex: 'onboardingStatus',
      render: (_, row) =>
        row.onboardingStatus === 'configuring' ? (
          <Tag color="warning">
            {t('pages.ops.customers.status.configuring')}
          </Tag>
        ) : row.onboardingStatus === 'active' ? (
          <Tag color="success">{t('pages.ops.customers.status.active')}</Tag>
        ) : (
          // 旧版云端没有该字段：显示「未知」，不当成已开通
          <Tag>{t('pages.ops.customers.status.unknown')}</Tag>
        ),
    },
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
            {row.onboardingStatus === 'configuring' && (
              <a onClick={() => openOnboarding(row.id)}>
                {t('pages.ops.customers.action.onboarding')}
              </a>
            )}
            <a
              onClick={() =>
                history.push(`/assets/customers/${row.id}/accounts`)
              }
            >
              {t('pages.ops.customers.action.users')}
            </a>
            <a onClick={() => history.push(`/assets/media?customer=${row.id}`)}>
              {t('pages.ops.customers.action.media')}
            </a>
            <a onClick={() => setBrandTarget(row)}>
              {t('pages.ops.customers.action.brand')}
            </a>
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
        columns={columns}
        search={false}
        loading={loading}
        dataSource={filterByKeyword(rows, search.keyword, matchCustomer)}
        options={{ density: false, setting: false, reload }}
        pagination={search.pagination}
        locale={{
          emptyText: t(
            search.searching && rows.length > 0
              ? 'pages.assets.search.empty'
              : 'pages.ops.customers.empty',
          ),
        }}
        headerTitle={
          <KeywordSearchBox
            placeholder={t('pages.ops.customers.search')}
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
            {t('pages.ops.customers.create')}
          </Button>,
        ]}
      />
      <CreateCustomerModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onDone={(created) => {
          // 只有云端明确回报「配置中」才进向导；旧版云端（无 onboardingStatus）没有向导接口，进去只会 404
          if (created.onboardingStatus === 'configuring')
            openOnboarding(created.id);
          else reload();
        }}
        onPartial={reload}
      />
      <BrandLogoModal
        customer={brandTarget}
        onClose={() => setBrandTarget(undefined)}
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
