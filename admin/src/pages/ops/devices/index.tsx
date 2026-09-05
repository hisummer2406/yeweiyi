import { PageContainer } from '@ant-design/pro-components';

/**
 * 设备全量视图：对接 yeweiyi-cloud 的 GET /api/admin/devices。
 * 跨客户查看全部设备及其绑定账号，仅 platform_admin 可见。
 */
const AdminDevices: React.FC = () => {
  return <PageContainer title="设备全量视图" />;
};

export default AdminDevices;
