/**
 * @see https://umijs.org/docs/max/access#access
 *
 * 角色与 yeweiyi-cloud 的 JWT claims 一致（见 internal/control/*、internal/api/admin.go）：
 * platform_admin / customer_admin / customer_user。平台管理员与客户管理员登录同一个后台（员工只用小程序，
 * 不能进入），运维分区（/ops/*）只对 platform_admin 开放，客户监控分区（/console/*）两种管理员都能进，
 * 资产管理（/console/assets/*：客户·油站·设备）对平台管理员和客户管理员开放，其中客户页只对平台管理员开放。
 * */
export default function access(
  initialState: { currentUser?: API.CurrentUser } | undefined,
) {
  const { currentUser } = initialState ?? {};
  const role = currentUser?.access;
  return {
    canPlatformAdmin: role === 'platform_admin',
    canManageAssets: role === 'platform_admin' || role === 'customer_admin',
    // 员工（customer_user）只用小程序，没有网页后台入口（yeweiyi-cloud#59）。这只是界面可见性，
    // 云端对管理接口的角色校验才是边界；员工的业务 REST 仍对小程序开放。
    canConsole: role === 'platform_admin' || role === 'customer_admin',
  };
}
