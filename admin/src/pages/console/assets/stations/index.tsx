import { PageContainer } from '@ant-design/pro-components';
import type React from 'react';
import StationsTab from './StationsTab';

/**
 * 油站管理（/assets/stations）：平台管理员和客户管理员可进（access: canManageAssets）。
 * 只管油站本身的增删改；给设备分配油站在 /assets/devices。
 */
const ConsoleStations: React.FC = () => (
  <PageContainer>
    <StationsTab />
  </PageContainer>
);

export default ConsoleStations;
