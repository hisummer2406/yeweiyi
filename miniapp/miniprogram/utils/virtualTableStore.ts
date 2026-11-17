import { roleFromToken, userKeyFromToken } from './auth';
import { canUseVirtual, legacyScaleKeys, readVirtual, virtualKey, type VirtualTable } from './virtualTable';
import { getToken } from './storage';

// 虚拟罐表的读写（Issue #56）：只在 wx.storage，不上云、不下发。每次都从当前 token 取账号和角色，
// 所以换账号、角色变成员工后，旧账号的表既读不到也不会被应用。

function access(device: string, tank: number): { allowed: boolean; key: string } {
  const token = getToken();
  if (!canUseVirtual(roleFromToken(token))) return { allowed: false, key: '' };
  const key = virtualKey(userKeyFromToken(token), device, tank);
  return { allowed: !!key, key };
}

export function virtualAllowed(device: string, tank: number): boolean {
  return access(device, tank).allowed;
}

// 当前账号对这个罐生效的虚拟表；无权限、未启用、存储损坏或读取失败都是 null（设备原值）。
export function loadVirtual(device: string, tank: number): VirtualTable | null {
  const { allowed, key } = access(device, tank);
  if (!allowed) return null;
  try {
    return readVirtual(wx.getStorageSync(key));
  } catch {
    return null;
  }
}

// 写入成功才返回 true；失败时调用方必须提示失败，不能说已启用。
export function saveVirtual(device: string, tank: number, table: VirtualTable): boolean {
  const { allowed, key } = access(device, tank);
  if (!allowed) return false;
  try {
    wx.setStorageSync(key, table);
    return true;
  } catch {
    return false;
  }
}

// 停用：删掉记录，恢复设备原值。删除失败返回 false。
export function removeVirtual(device: string, tank: number): boolean {
  const { allowed, key } = access(device, tank);
  if (!allowed) return false;
  try {
    wx.removeStorageSync(key);
    return true;
  } catch {
    return false;
  }
}

// 旧 #44 显示倍率：当前账号若还留着倍率配置，清掉并返回 true（页面提示一次「倍率已下线」）。
// 不把倍率转成虚拟表——一个系数定不出罐型和液位—容积曲线。
export function dropLegacyScale(): boolean {
  const account = userKeyFromToken(getToken());
  let keys: string[] = [];
  try {
    keys = legacyScaleKeys(wx.getStorageInfoSync().keys, account);
  } catch {
    return false;
  }
  for (const k of keys) {
    try {
      wx.removeStorageSync(k);
    } catch {
      // 删不掉也不再参与计算
    }
  }
  return keys.length > 0;
}
