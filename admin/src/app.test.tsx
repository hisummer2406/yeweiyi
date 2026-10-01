import { beforeEach, describe, expect, it, vi } from 'vitest';

// Mock all heavy dependencies before importing app
const mockReplace = vi.fn();
const mockHistory = {
  location: {
    pathname: '/welcome',
    search: '',
    hash: '',
  },
  replace: mockReplace,
};

const mockGetToken = vi.fn();
const mockDecodeToken = vi.fn();
const mockIsTokenExpired = vi.fn();
const mockClearToken = vi.fn();

vi.mock('@umijs/max', () => ({
  history: mockHistory,
  Link: ({ children }: any) => children,
}));

vi.mock('@/utils/auth', () => ({
  getToken: mockGetToken,
  decodeToken: mockDecodeToken,
  isTokenExpired: mockIsTokenExpired,
  clearToken: mockClearToken,
}));

vi.mock('@/components', () => ({
  AvatarDropdown: () => null,
  ErrorBoundary: ({ children }: any) => children,
  Footer: () => null,
  LangDropdown: () => null,
  OfflineBanner: () => null,
  VersionDropdown: () => null,
}));

vi.mock('@ant-design/pro-components', () => ({
  SettingDrawer: () => null,
}));

vi.mock('@ant-design/icons', () => ({
  LinkOutlined: () => null,
}));

vi.mock('./requestErrorConfig', () => ({
  errorConfig: {},
}));

vi.mock('../config/defaultSettings', () => ({
  default: { navTheme: 'light' },
}));

const validClaims = {
  uid: 4402,
  role: 'customer_admin',
  sub: 'test-user',
  exp: 9999999999,
  iat: 1,
};

describe('app getInitialState', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockHistory.location = {
      pathname: '/welcome',
      search: '',
      hash: '',
    };
  });

  it('should build currentUser from a valid stored token when not on login page', async () => {
    const { getInitialState } = await import('./app');
    mockGetToken.mockReturnValue('a.b.c');
    mockDecodeToken.mockReturnValue(validClaims);
    mockIsTokenExpired.mockReturnValue(false);

    const state = await getInitialState();

    expect(state.currentUser).toEqual({ access: 'customer_admin', name: 'test-user' });
    expect(state.settingDrawerOpen).toBe(false);
    expect(state.fetchUserInfo).toBeDefined();
    expect(mockReplace).not.toHaveBeenCalled();
    expect(mockClearToken).not.toHaveBeenCalled();
  });

  it('should redirect to login when no token is stored', async () => {
    const { getInitialState } = await import('./app');
    mockGetToken.mockReturnValue(null);

    const state = await getInitialState();

    expect(mockReplace).toHaveBeenCalledWith(
      expect.stringContaining('/user/login?redirect='),
    );
    expect(state.currentUser).toBeUndefined();
    expect(mockClearToken).not.toHaveBeenCalled();
  });

  it('should clear the token and redirect when the stored token is expired', async () => {
    const { getInitialState } = await import('./app');
    mockGetToken.mockReturnValue('a.b.c');
    mockDecodeToken.mockReturnValue(validClaims);
    mockIsTokenExpired.mockReturnValue(true);

    const state = await getInitialState();

    expect(mockClearToken).toHaveBeenCalled();
    expect(mockReplace).toHaveBeenCalledWith(
      expect.stringContaining('/user/login?redirect='),
    );
    expect(state.currentUser).toBeUndefined();
  });

  it('should clear the token and redirect when the stored token is malformed', async () => {
    const { getInitialState } = await import('./app');
    mockGetToken.mockReturnValue('not-a-jwt');
    mockDecodeToken.mockReturnValue(null);

    const state = await getInitialState();

    expect(mockClearToken).toHaveBeenCalled();
    expect(mockReplace).toHaveBeenCalledWith(
      expect.stringContaining('/user/login?redirect='),
    );
    expect(state.currentUser).toBeUndefined();
  });

  it('should not decode a token on the login page', async () => {
    const { getInitialState } = await import('./app');
    mockHistory.location = {
      pathname: '/user/login',
      search: '',
      hash: '',
    };

    const state = await getInitialState();

    expect(mockGetToken).not.toHaveBeenCalled();
    expect(state.currentUser).toBeUndefined();
    expect(state.fetchUserInfo).toBeDefined();
  });

  it('should encode redirect path correctly when no token is stored', async () => {
    const { getInitialState } = await import('./app');
    mockHistory.location = {
      pathname: '/admin/users',
      search: '?page=2',
      hash: '#section',
    };
    mockGetToken.mockReturnValue(null);

    await getInitialState();

    expect(mockReplace).toHaveBeenCalledWith(
      `/user/login?redirect=${encodeURIComponent('/admin/users?page=2#section')}`,
    );
  });

  it('should include default settings in initial state', async () => {
    const { getInitialState } = await import('./app');
    mockGetToken.mockReturnValue('a.b.c');
    mockDecodeToken.mockReturnValue(validClaims);
    mockIsTokenExpired.mockReturnValue(false);

    const state = await getInitialState();

    expect(state.settings).toEqual({ navTheme: 'light' });
  });

  it('fetchUserInfo should return user data on success', async () => {
    const { getInitialState } = await import('./app');
    mockGetToken.mockReturnValue('a.b.c');
    mockDecodeToken.mockReturnValue(validClaims);
    mockIsTokenExpired.mockReturnValue(false);

    const state = await getInitialState();

    const user = await state.fetchUserInfo?.();
    expect(user).toEqual({ access: 'customer_admin', name: 'test-user' });
  });
});
