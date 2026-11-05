import { PageContainer } from '@ant-design/pro-components';
import type React from 'react';
import UsersPanel from '../users/UsersPanel';

/**
 * 平台管理员账号（仅 platform_admin）：入口在右上角账号菜单，不在侧边栏。只列、只建平台管理员；
 * 客户管理员随「新建客户」创建，客户侧账号（含员工）在「运维管理 → 账号管理」。
 */
const PlatformAdmins: React.FC = () => (
  <PageContainer>
    <UsersPanel platformOnly />
  </PageContainer>
);

export default PlatformAdmins;
