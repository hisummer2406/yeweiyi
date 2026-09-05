import { PageContainer } from '@ant-design/pro-components';

/**
 * 账号管理：对接 yeweiyi-cloud 的 POST/GET /api/admin/users、
 * PATCH /api/admin/users/:id/role。仅 platform_admin 可见（见 access.ts）。
 */
const AdminUsers: React.FC = () => {
  return <PageContainer title="账号管理" />;
};

export default AdminUsers;
