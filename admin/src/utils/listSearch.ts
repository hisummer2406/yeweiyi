/**
 * 资产管理列表的关键词搜索（#108）：四页都是一次取全、在前端分页，所以搜索也在前端、在分页之前做。
 * 关键词去掉首尾空格、忽略大小写；名称、地址、设备编号、手机号按字符串片段匹配（编号、手机号保留前导零，
 * 不转数字）。客户 ID 只认完整输入。所属客户名只有平台管理员的行才带（客户管理员不请求客户列表），
 * 所以客户管理员自然搜不到别的客户，也不会按客户名命中。
 */

export function normalizeKeyword(raw: string): string {
  return raw.trim().toLowerCase();
}

function includes(value: string | null | undefined, kw: string): boolean {
  return (value ?? '').toLowerCase().includes(kw);
}

/** 客户：名称片段，或完整的客户 ID */
export function matchCustomer(
  c: Pick<API.Customer, 'id' | 'name'>,
  kw: string,
): boolean {
  return includes(c.name, kw) || String(c.id) === kw;
}

/** 油站：名称、地址；平台管理员的行另有所属客户名 */
export function matchStation(
  s: { name: string; address?: string | null; customerName?: string },
  kw: string,
): boolean {
  return (
    includes(s.name, kw) ||
    includes(s.address, kw) ||
    includes(s.customerName, kw)
  );
}

/** 设备：设备编号、所属油站名；平台管理员的行另有所属客户名 */
export function matchDevice(
  d: { device_name: string; stationName?: string; customerName?: string },
  kw: string,
): boolean {
  return (
    includes(d.device_name, kw) ||
    includes(d.stationName, kw) ||
    includes(d.customerName, kw)
  );
}

/** 员工：手机号、所属客户名 */
export function matchStaff(
  u: { phone?: string | null; customerName?: string },
  kw: string,
): boolean {
  return includes(u.phone, kw) || includes(u.customerName, kw);
}

/** 关键词为空返回原数组；否则按 match 过滤（match 收到的是规范化后的关键词） */
export function filterByKeyword<T>(
  rows: T[],
  raw: string,
  match: (row: T, kw: string) => boolean,
): T[] {
  const kw = normalizeKeyword(raw);
  return kw ? rows.filter((r) => match(r, kw)) : rows;
}
