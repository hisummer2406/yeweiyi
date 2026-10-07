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
    icon: 'safetyCertificate',
    access: 'canPlatformAdmin',
    routes: [
      {
        path: '/ops',
        redirect: '/ops/devices',
      },
      {
        name: 'devices',
        icon: 'cluster',
        path: '/ops/devices',
        component: './ops/devices',
      },
      {
        name: 'customers',
        icon: 'shop',
        path: '/ops/customers',
        component: './ops/customers',
      },
      {
        name: 'users',
        icon: 'team',
        path: '/ops/users',
        component: './ops/users',
      },
      {
        name: 'pairing-secret',
        icon: 'key',
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
    // 菜单分组参照设备端首页底栏与小程序的信息架构：
    // 设备总览（首页）/ 记录查询（报警·入库·出库）/ 远程控制。
    // 交班记录、库存记录、各类设置在云端暂无接口（小程序里也是置灰入口），不放进菜单。
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
        name: 'records',
        icon: 'profile',
        path: '/console/records',
        routes: [
          {
            path: '/console/records',
            redirect: '/console/records/alarms',
          },
          {
            name: 'alarms',
            icon: 'alert',
            path: '/console/records/alarms',
            component: './console/alarms',
          },
          {
            name: 'stock-in',
            icon: 'download',
            path: '/console/records/stock-in',
            component: './console/oil-in-out',
          },
          {
            name: 'stock-out',
            icon: 'upload',
            path: '/console/records/stock-out',
            component: './console/oil-in-out',
          },
        ],
      },
      {
        name: 'issue-command',
        icon: 'control',
        path: '/console/issue-command',
        component: './console/issue-command',
      },
    ],
  },
  {
    name: 'account',
    icon: 'user',
    path: '/account',
    hideInMenu: true,
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
    hideInMenu: true,
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
