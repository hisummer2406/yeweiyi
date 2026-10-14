// The station the user last picked on the home page, per account: keyed by the account's user key
// (`userKeyFromToken`, "u12") so switching accounts on one phone does not restore somebody else's
// station. An empty key (no usable token) stores and restores nothing. Reads and writes are best
// effort — storage can be unavailable (private mode, cleared data) and the page must still work.
const prefKey = (userKey: string) => `station:${userKey}`;

export function getRememberedStation(userKey: string): string | undefined {
  if (!userKey) return undefined;
  try {
    const v = wx.getStorageSync<string>(prefKey(userKey));
    return v || undefined;
  } catch {
    return undefined;
  }
}

export function rememberStation(userKey: string, key: string): void {
  if (!userKey) return;
  try {
    wx.setStorageSync(prefKey(userKey), key);
  } catch {
    // not remembered; next launch falls back to the default
  }
}
