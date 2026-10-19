const TOKEN_KEY = 'token';
const PROFILE_KEY = 'profile';
const AUTO_LOGIN_KEY = 'autoLogin';
const LOCALE_KEY = 'locale';

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
  clearLocalDrafts();
  try {
    wx.removeStorageSync(PROFILE_KEY);
  } catch {
    // nothing to clear
  }
}

// The static settings pages keep a draft on the phone (calibration offsets, tank-chart dimensions). They
// are not tied to an account, so they must not outlive the session or the next account would open the
// previous account's numbers.
const DRAFT_KEYS = ['calibration-draft', 'tankshape-draft-horizontal', 'tankshape-draft-vertical', 'tankshape-draft-cuboid'];

function clearLocalDrafts(): void {
  for (const key of DRAFT_KEYS) {
    try {
      wx.removeStorageSync(key);
    } catch {
      // nothing to clear
    }
  }
}

// What login returned about the user, for display only. Identity for keys is the token's uid.
export interface StoredProfile {
  userId: number;
  phone: string;
  // Set for accounts that sign in by a username (platform admins today; customer-side ones once the cloud
  // gives them one, cloud#77). Empty otherwise.
  username?: string;
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

// The language the user picked, or undefined when they never did (the phone's language is used then).
// Not part of a session: it survives logout, so the login page still speaks the language chosen.
export function getLocalePref(): string | undefined {
  try {
    const v = wx.getStorageSync<string>(LOCALE_KEY);
    return typeof v === 'string' && v ? v : undefined;
  } catch {
    return undefined;
  }
}

export function setLocalePref(locale: string): void {
  try {
    wx.setStorageSync(LOCALE_KEY, locale);
  } catch {
    // the choice then only lasts for this run
  }
}
