/**
 * @see https://umijs.org/docs/max/access#access
 *
 * 角色与 yeweiyi-cloud 的 JWT claims 一致（见 internal/control/*、internal/api/admin.go）：
 * platform_admin / customer_admin / customer_user。三者都能登录同一个后台，
 * 运维分区（/ops/*）只对 platform_admin 开放，客户监控分区（/console/*）三种角色都能进。
 * */
export default function access(
  initialState: { currentUser?: API.CurrentUser } | undefined,
) {
  const { currentUser } = initialState ?? {};
  const role = currentUser?.access;
  return {
    canPlatformAdmin: role === 'platform_admin',
    canConsole:
      role === 'platform_admin' ||
      role === 'customer_admin' ||
      role === 'customer_user',
  };
}
