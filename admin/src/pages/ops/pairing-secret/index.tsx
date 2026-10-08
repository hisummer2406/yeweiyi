import { PageContainer } from '@ant-design/pro-components';

/**
 * 配对密钥铸造：对接 yeweiyi-cloud 的 POST /api/devices/:device/pairing-secret。
 * 明文密钥只在铸造响应中出现一次，用于生成设备物理二维码。
 * 密钥现在只用于「带密钥绑定」（含平台管理员跨客户的场景），不是扫码绑定的必需项：
 * 二维码只含短码时，客户账号凭「设备已分配给其所属客户」即可绑定（见 CLAUDE.md）。
 * 每次铸造都会轮换密钥，已绑定的用户不受影响。
 */
const PairingSecret: React.FC = () => {
  return <PageContainer title="配对密钥" />;
};

export default PairingSecret;
