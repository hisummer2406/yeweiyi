export type UserFilter = {
  /** 手机号/用户名子串；手机号按数字比较，输入里的空格、横线忽略 */
  keyword: string;
  customerId?: number;
  role?: API.UserRole;
  /** 'active' | 'disabled'；省略为全部 */
  status?: 'active' | 'disabled';
};

const ROLES: API.UserRole[] = [
  'platform_admin',
  'customer_admin',
  'customer_user',
];

/** 从 URL 查询参数还原筛选条件；非法值一律当作未设置，不报错。 */
export function parseUserFilter(search: string): UserFilter {
  const p = new URLSearchParams(search);
  const cid = p.get('customer');
  const role = p.get('role');
  const status = p.get('status');
  return {
    keyword: p.get('q') ?? '',
    customerId: cid && /^\d+$/.test(cid) ? Number(cid) : undefined,
    role: ROLES.includes(role as API.UserRole)
      ? (role as API.UserRole)
      : undefined,
    status: status === 'active' || status === 'disabled' ? status : undefined,
  };
}

export function serializeUserFilter(f: UserFilter): string {
  const p = new URLSearchParams();
  if (f.keyword.trim()) p.set('q', f.keyword.trim());
  if (f.customerId != null) p.set('customer', String(f.customerId));
  if (f.role) p.set('role', f.role);
  if (f.status) p.set('status', f.status);
  const s = p.toString();
  return s ? `?${s}` : '';
}

export function filterUsers<
  T extends Pick<
    API.AdminUser,
    'username' | 'phone' | 'role' | 'customerId' | 'disabled'
  >,
>(users: T[], f: UserFilter): T[] {
  const kw = f.keyword.trim().toLowerCase();
  const digits = kw.replace(/[\s-]/g, '');
  return users.filter((u) => {
    if (f.customerId != null && u.customerId !== f.customerId) return false;
    if (f.role && u.role !== f.role) return false;
    if (f.status === 'active' && u.disabled) return false;
    if (f.status === 'disabled' && !u.disabled) return false;
    if (!kw) return true;
    return (
      (u.username ?? '').toLowerCase().includes(kw) ||
      (u.phone ?? '').includes(digits)
    );
  });
}
