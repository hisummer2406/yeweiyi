/**
 * @see https://umijs.org/docs/max/access#access
 *
 * 角色与 yeweiyi-cloud 的 JWT claims 一致（见 internal/control/*、internal/api/admin.go）：
 * platform_admin / customer_admin / customer_user。三者都能登录同一个后台，
 * 运维分区（/ops/*）只对 platform_admin 开放，客户监控分区（/console/*）三种角色都能进，
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
    canConsole:
      role === 'platform_admin' ||
      role === 'customer_admin' ||
      role === 'customer_user',
  };
}
