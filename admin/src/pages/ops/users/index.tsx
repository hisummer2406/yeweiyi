import { PageContainer } from '@ant-design/pro-components';
import type React from 'react';
import UsersPanel from './UsersPanel';

/**
 * 账号管理（仅 platform_admin）：客户侧账号（客户管理员、员工）的跨客户视图——搜索、筛选、新建员工、
 * 重置密码、停用。平台管理员账号不在这里，见右上角账号菜单（`/ops/platform-admins`）。
 */
const AdminUsers: React.FC = () => (
  <PageContainer>
    <UsersPanel />
  </PageContainer>
);

export default AdminUsers;
