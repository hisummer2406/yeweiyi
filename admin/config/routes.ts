/**
 * @name umi 的路由配置
 * @description 只支持 path,component,routes,redirect,wrappers,name,icon 的配置
 * @doc https://umijs.org/docs/guides/routes
 *
 * 两个分区，同一个应用，靠 access 区分（详见项目根 CLAUDE.md「两个前台，一个仓库」）：
 * - /ops/*      运维/管理控制台，仅 platform_admin（access.ts 的 canPlatformAdmin）
 * - /console/*  客户 PC 监控台，小程序的桌面版，三种角色都能进（canConsole）
 */
export default [
  {
    path: '/user',
    layout: false,
    routes: [
      {
        path: '/user/login',
        name: 'login',
        component: './user/login',
      },
      {
        path: '/user',
        redirect: '/user/login',
      },
      {
        name: '404',
        component: './exception/404',
        path: '/user/*',
      },
    ],
  },
  {
    path: '/ops',
    name: 'ops',
    icon: 'crown',
    access: 'canPlatformAdmin',
    routes: [
      {
        path: '/ops',
        redirect: '/ops/users',
      },
      {
        name: 'users',
        icon: 'team',
        path: '/ops/users',
        component: './ops/users',
      },
      {
        name: 'devices',
        icon: 'cluster',
        path: '/ops/devices',
        component: './ops/devices',
      },
      {
        name: 'pairing-secret',
        icon: 'qrcode',
        path: '/ops/pairing-secret',
        component: './ops/pairing-secret',
      },
      {
        name: 'audit',
        icon: 'fileSearch',
        path: '/ops/audit',
        component: './ops/audit',
      },
    ],
  },
  {
    path: '/console',
    name: 'console',
    icon: 'desktop',
    access: 'canConsole',
    routes: [
      {
        path: '/console',
        redirect: '/console/devices',
      },
      {
        name: 'devices',
        icon: 'appstore',
        path: '/console/devices',
        component: './console/devices',
      },
      {
        name: 'device-detail',
        path: '/console/devices/:deviceName',
        component: './console/device-detail',
        hideInMenu: true,
      },
      {
        name: 'issue-command',
        icon: 'sliders',
        path: '/console/issue-command',
        component: './console/issue-command',
      },
      {
        name: 'oil-in-out',
        icon: 'swap',
        path: '/console/oil-in-out',
        component: './console/oil-in-out',
      },
    ],
  },
  {
    name: 'account',
    icon: 'user',
    path: '/account',
    routes: [
      {
        path: '/account',
        redirect: '/account/settings',
      },
      {
        name: 'settings',
        icon: 'setting',
        path: '/account/settings',
        component: './account/settings',
      },
    ],
  },
  {
    name: 'exception',
    icon: 'warning',
    path: '/exception',
    routes: [
      {
        path: '/exception',
        redirect: '/exception/403',
      },
      {
        name: '403',
        icon: 'stop',
        path: '/exception/403',
        component: './exception/403',
      },
      {
        name: '404',
        icon: 'warning',
        path: '/exception/404',
        component: './exception/404',
      },
      {
        name: '500',
        icon: 'bug',
        path: '/exception/500',
        component: './exception/500',
      },
    ],
  },
  {
    path: '/',
    redirect: '/console/devices',
  },
  {
    component: './exception/404',
    path: '/*',
  },
];
