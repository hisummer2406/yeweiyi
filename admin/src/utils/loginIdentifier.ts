/**
 * 登录框里的输入该发给云端的哪个字段。云端不替客户端猜：`username`（平台管理员）与 `phone`
 * （客户管理员）必须恰好给一个。大陆手机号规则与云端 internal/api/phone.go 一致：去掉空格、连字符和
 * +86 / 0086 / 86 前缀后是 `1[3-9]` 开头的 11 位数字。其余一律当用户名。
 */
const PHONE = /^1[3-9]\d{9}$/;

export function normalizePhone(raw: string): string | undefined {
  const s = raw.replace(/[\s-]/g, '');
  for (const prefix of ['+86', '0086', '86']) {
    if (s.startsWith(prefix) && PHONE.test(s.slice(prefix.length))) {
      return s.slice(prefix.length);
    }
  }
  return PHONE.test(s) ? s : undefined;
}

export function buildLoginParams(
  identifier: string,
  password: string,
): API.LoginParams {
  const trimmed = identifier.trim();
  const phone = normalizePhone(trimmed);
  return phone ? { phone, password } : { username: trimmed, password };
}
