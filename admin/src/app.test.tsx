import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mockReplace = vi.fn();
const mockHistory = {
  location: {
    pathname: '/console/devices',
    search: '',
    hash: '',
  },
  replace: mockReplace,
};

vi.mock('@umijs/max', () => ({
  history: mockHistory,
  Link: ({ children }: any) => children,
}));

vi.mock('@/components', () => ({
  AvatarDropdown: () => null,
  ErrorBoundary: ({ children }: any) => children,
  Footer: () => null,
  LangDropdown: () => null,
  OfflineBanner: () => null,
}));

vi.mock('@ant-design/pro-components', () => ({
  SettingDrawer: () => <div data-testid="layout-settings" />,
}));

vi.mock('./requestErrorConfig', () => ({
  errorConfig: {},
}));

vi.mock('../config/defaultSettings', () => ({
  default: { navTheme: 'light' },
}));

const b64url = (obj: unknown) =>
  btoa(JSON.stringify(obj))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
const makeToken = (claims: Record<string, unknown>) =>
  `${b64url({ alg: 'HS256' })}.${b64url(claims)}.sig`;
const inOneHour = Math.floor(Date.now() / 1000) + 3600;

const { setToken, clearToken } = await import('@/utils/auth');

describe('layout settings environment', () => {
  afterEach(() => vi.unstubAllEnvs());

  it.each(['production', 'development'])(
    'limits the drawer in %s',
    async (env) => {
      vi.stubEnv('NODE_ENV', env);
      const { layout } = await import('./app');
      const config = layout({
        initialState: { settingDrawerOpen: true },
        setInitialState: vi.fn(),
      } as any);
      const html = renderToStaticMarkup(
        config.childrenRender?.(<div>page</div>, {} as any) as any,
      );
      expect(html.includes('layout-settings')).toBe(env === 'development');
    },
  );
});

describe('app getInitialState', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    clearToken();
    mockHistory.location = {
      pathname: '/console/devices',
      search: '',
      hash: '',
    };
  });

  it('derives currentUser from a valid token off the login page', async () => {
    const { getInitialState } = await import('./app');
    setToken(
      makeToken({
        uid: 3,
        sub: 'alice',
        role: 'customer_admin',
        exp: inOneHour,
      }),
    );

    const state = await getInitialState();

    expect(state.currentUser).toEqual({
      userId: 3,
      username: 'alice',
      access: 'customer_admin',
    });
    expect(mockReplace).not.toHaveBeenCalled();
    expect(state.settingDrawerOpen).toBe(false);
    expect(state.settings).toEqual({ navTheme: 'light' });
  });

  it('redirects to login when there is no token', async () => {
    const { getInitialState } = await import('./app');

    const state = await getInitialState();

    expect(mockReplace).toHaveBeenCalledWith(
      `/user/login?redirect=${encodeURIComponent('/console/devices')}`,
    );
    expect(state.currentUser).toBeUndefined();
  });

  it('redirects and clears an expired token', async () => {
    const { getInitialState } = await import('./app');
    setToken(makeToken({ uid: 3, sub: 'a', role: 'customer_user', exp: 1 }));

    const state = await getInitialState();

    expect(mockReplace).toHaveBeenCalled();
    expect(state.currentUser).toBeUndefined();
    expect(window.localStorage.getItem('yeweiyi_admin_token')).toBeNull();
  });

  it('does not derive currentUser or redirect on the login page', async () => {
    const { getInitialState } = await import('./app');
    mockHistory.location = { pathname: '/user/login', search: '', hash: '' };

    const state = await getInitialState();

    expect(mockReplace).not.toHaveBeenCalled();
    expect(state.currentUser).toBeUndefined();
    expect(state.fetchUserInfo).toBeDefined();
  });

  it('encodes the redirect path with query and hash', async () => {
    const { getInitialState } = await import('./app');
    mockHistory.location = {
      pathname: '/ops/users',
      search: '?page=2',
      hash: '#section',
    };

    await getInitialState();

    expect(mockReplace).toHaveBeenCalledWith(
      `/user/login?redirect=${encodeURIComponent('/ops/users?page=2#section')}`,
    );
  });

  it('fetchUserInfo re-derives the user from the stored token', async () => {
    const { getInitialState } = await import('./app');
    mockHistory.location = { pathname: '/user/login', search: '', hash: '' };
    const state = await getInitialState();
    expect(await state.fetchUserInfo?.()).toBeUndefined();

    setToken(
      makeToken({
        uid: 1,
        sub: 'root',
        role: 'platform_admin',
        exp: inOneHour,
      }),
    );
    expect(await state.fetchUserInfo?.()).toEqual({
      userId: 1,
      username: 'root',
      access: 'platform_admin',
    });
  });
});
