import { Navigate, useAccess } from '@umijs/max';
import type React from 'react';

/**
 * 资产管理入口（/console/assets）：落到第一个可见项。路由表里的 redirect 是静态的，
 * 但客户项只对平台管理员可见，所以按角色在这里分流。
 */
const AssetsIndex: React.FC = () => {
  const { canPlatformAdmin } = useAccess();
  return (
    <Navigate
      to={
        canPlatformAdmin
          ? '/console/assets/customers'
          : '/console/assets/stations'
      }
      replace
    />
  );
};

export default AssetsIndex;
