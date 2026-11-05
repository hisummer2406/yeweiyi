import { PageContainer } from '@ant-design/pro-components';

/**
 * 个人设置：修改自己的密码等。不做找回密码——这套账号体系没有自助注册/找回入口，
 * 忘记密码只能找 platform_admin 用 PATCH /api/admin/users/:id/role 相邻的账号管理能力处理
 * （具体交互留到实现阶段设计，这里先占位）。
 */
const AccountSettings: React.FC = () => {
  return <PageContainer title="个人设置" />;
};

export default AccountSettings;
