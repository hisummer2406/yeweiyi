// The station the user last picked on the home page, per account: keyed by username so switching
// accounts on one phone does not restore somebody else's station. Reads and writes are best effort —
// storage can be unavailable (private mode, cleared data) and the page must still work.
const prefKey = (username: string) => `station:${username}`;

export function getRememberedStation(username: string): string | undefined {
  try {
    const v = wx.getStorageSync<string>(prefKey(username));
    return v || undefined;
  } catch {
    return undefined;
  }
}

export function rememberStation(username: string, key: string): void {
  try {
    wx.setStorageSync(prefKey(username), key);
  } catch {
    // not remembered; next launch falls back to the default
  }
}
