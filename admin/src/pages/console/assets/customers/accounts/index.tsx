import { PageContainer } from '@ant-design/pro-components';
import { history, useIntl, useParams } from '@umijs/max';
import { Result } from 'antd';
import type React from 'react';
import { useEffect, useState } from 'react';
import UsersPanel from '@/pages/ops/users/UsersPanel';
import { listCustomers } from '@/services/ant-design-pro/customers';

/**
 * 客户下的账号（资产管理 → 客户 → 账号，仅 platform_admin，不进菜单）：该客户的管理员与员工，
 * 新建时客户自动锁定。账号接口与「账号管理」相同，只是入口按客户组织；云端不提供按客户的查询，
 * 仍是取全后前端过滤。
 */
const CustomerAccounts: React.FC = () => {
  const intl = useIntl();
  const { id } = useParams<{ id: string }>();
  const customerId = id && /^\d+$/.test(id) ? Number(id) : undefined;
  const [name, setName] = useState<string>();

  useEffect(() => {
    if (customerId == null) return;
    // 名字只用于标题，取不到退化成 #id，不拦账号列表
    listCustomers()
      .then((cs) => setName(cs.find((c) => c.id === customerId)?.name))
      .catch(() => undefined);
  }, [customerId]);

  if (customerId == null) {
    return (
      <Result
        status="404"
        title={intl.formatMessage({
          id: 'pages.ops.customers.accounts.notFound',
        })}
      />
    );
  }
  return (
    <PageContainer
      title={intl.formatMessage(
        { id: 'pages.ops.customers.accounts.title' },
        { name: name ?? `#${customerId}` },
      )}
      onBack={() => history.push('/assets/customers')}
    >
      <UsersPanel customerId={customerId} />
    </PageContainer>
  );
};

export default CustomerAccounts;
