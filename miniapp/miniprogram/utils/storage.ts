const TOKEN_KEY = 'token';
const PROFILE_KEY = 'profile';
const AUTO_LOGIN_KEY = 'autoLogin';

export function getToken(): string {
  try {
    return wx.getStorageSync<string>(TOKEN_KEY) || '';
  } catch {
    return '';
  }
}

export function setToken(token: string): void {
  wx.setStorageSync(TOKEN_KEY, token);
}

// Ends the session. The cached profile goes with the token so one account's number can never be
// shown under another's login.
export function clearToken(): void {
  wx.removeStorageSync(TOKEN_KEY);
  try {
    wx.removeStorageSync(PROFILE_KEY);
  } catch {
    // nothing to clear
  }
}

// What login returned about the user, for display only. Identity for keys is the token's uid.
export interface StoredProfile {
  userId: number;
  phone: string;
  role: string;
}

export function setProfile(profile: StoredProfile): void {
  try {
    wx.setStorageSync(PROFILE_KEY, profile);
  } catch {
    // display falls back to "--"
  }
}

export function getProfile(): StoredProfile | undefined {
  try {
    const p = wx.getStorageSync<StoredProfile>(PROFILE_KEY);
    return p && typeof p.userId === 'number' ? p : undefined;
  } catch {
    return undefined;
  }
}

// After an explicit 退出登录 the login page must not silently sign the same WeChat straight back in
// (the cloud would happily restore it), or logging out would be impossible. It is switched back on by
// the next successful login. Absent = on.
export function autoLoginEnabled(): boolean {
  try {
    return wx.getStorageSync<boolean>(AUTO_LOGIN_KEY) !== false;
  } catch {
    return true;
  }
}

export function setAutoLogin(on: boolean): void {
  try {
    wx.setStorageSync(AUTO_LOGIN_KEY, on);
  } catch {
    // worst case the user is signed in again silently
  }
}
