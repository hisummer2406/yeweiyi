import { PageContainer } from '@ant-design/pro-components';

/**
 * 配对密钥铸造：对接 yeweiyi-cloud 的 POST /api/devices/:device/pairing-secret。
 * 明文密钥只在铸造响应中出现一次，用于生成设备物理二维码。
 */
const PairingSecret: React.FC = () => {
  return <PageContainer title="配对密钥" />;
};

export default PairingSecret;
