const TOKEN_KEY = 'token';

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

export function clearToken(): void {
  wx.removeStorageSync(TOKEN_KEY);
}
