import { roleFromToken, userKeyFromToken } from './auth';
import { activeFactor, canUseScale, DEFAULT_SCALE, readConfig, scaleKey, type ScaleConfig } from './displayScale';
import { getToken } from './storage';

// 本机显示倍率的读写（Issue #44）：只在 wx.storage，不上云、不下发。每次都从当前 token 取账号和角色，
// 所以换账号、角色变成员工后，旧账号的配置既读不到也不会被应用。

// 当前账号能否修改/应用倍率，以及存储键；不能时 key 为 ''。
function access(device: string, tank: number): { allowed: boolean; key: string } {
  const token = getToken();
  if (!canUseScale(roleFromToken(token))) return { allowed: false, key: '' };
  const key = scaleKey(userKeyFromToken(token), device, tank);
  return { allowed: !!key, key };
}

export function scaleAllowed(device: string, tank: number): boolean {
  return access(device, tank).allowed;
}

// 当前账号对这个罐的配置；无权限、无记录、存储损坏或读取失败都是默认值。
export function loadScale(device: string, tank: number): ScaleConfig {
  const { allowed, key } = access(device, tank);
  if (!allowed) return { ...DEFAULT_SCALE };
  try {
    return readConfig(wx.getStorageSync(key));
  } catch {
    return { ...DEFAULT_SCALE };
  }
}

// 生效的倍率（未启用或无权限为 undefined）。首页和详情都走这里，保证同一手机上一致。
export function scaleFactor(device: string, tank: number): number | undefined {
  return activeFactor(loadScale(device, tank));
}

// 写入成功才返回 true；失败时调用方必须提示保存失败，不能说已保存。默认配置直接删除记录。
export function saveScale(device: string, tank: number, cfg: ScaleConfig): boolean {
  const { allowed, key } = access(device, tank);
  if (!allowed) return false;
  try {
    if (!cfg.enabled && cfg.factor === 1) wx.removeStorageSync(key);
    else wx.setStorageSync(key, { enabled: cfg.enabled, factor: cfg.factor });
    return true;
  } catch {
    return false;
  }
}
