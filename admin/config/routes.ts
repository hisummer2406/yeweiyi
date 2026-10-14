/**
 * @name umi 的路由配置
 * @description 只支持 path,component,routes,redirect,wrappers,name,icon 的配置
 * @doc https://umijs.org/docs/guides/routes
 *
 * 两个分区，同一个应用，靠 access 区分（详见项目根 CLAUDE.md「两个前台，一个仓库」）：
 * - /ops/*      运维/管理控制台，仅 platform_admin（access.ts 的 canPlatformAdmin）
 * - /console/*  客户 PC 监控台，小程序的桌面版，三种角色都能进（canConsole）
 * - /records/*  记录查询（报警·入库·出库）、/assets/* 资产管理：与监控台并列的一级菜单，权限见各组 access
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
        redirect: '/ops/users',
      },
      // 设备登记与客户管理已并入「资产管理」，旧地址保留重定向
      {
        path: '/ops/devices',
        redirect: '/assets/devices',
      },
      {
        path: '/ops/customers',
        redirect: '/assets/customers',
      },
      {
        name: 'users',
        icon: 'team',
        path: '/ops/users',
        component: './ops/users',
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
    // 设备总览（首页）/ 记录查询（报警·入库·出库，下面单独成一级）/ 远程控制。
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
        name: 'issue-command',
        icon: 'control',
        path: '/console/issue-command',
        component: './console/issue-command',
      },
      // 旧地址保留重定向（书签、旧文档里的链接）：油站管理已移到「资产管理」，记录查询和资产管理
      // 已从 /console/* 搬到 /records/*、/assets/*。
      {
        path: '/console/stations',
        redirect: '/assets/stations',
      },
      {
        path: '/console/records',
        redirect: '/records/alarms',
      },
      {
        path: '/console/records/alarms',
        redirect: '/records/alarms',
      },
      {
        path: '/console/records/stock-in',
        redirect: '/records/stock-in',
      },
      {
        path: '/console/records/stock-out',
        redirect: '/records/stock-out',
      },
      {
        path: '/console/assets',
        redirect: '/assets',
      },
      {
        path: '/console/assets/customers',
        redirect: '/assets/customers',
      },
      {
        path: '/console/assets/stations',
        redirect: '/assets/stations',
      },
      {
        path: '/console/assets/devices',
        redirect: '/assets/devices',
      },
      {
        path: '/console/assets/staff',
        redirect: '/assets/staff',
      },
    ],
  },
  {
    // 记录查询单独成一级菜单：放在「设备监控」下面要点三层（设备监控 → 记录查询 → 报警记录），
    // 而且报警/出入库要先选设备，本来就不属于设备总览。URL 是 /records/*，不放在 /console/ 下：ProLayout 按前缀匹配菜单
    // （路径 P 会匹配 P/(.*)），分组 path 是 /console 时，/console/* 下的页面会把「设备监控」一并点亮。
    path: '/records',
    name: 'records',
    icon: 'profile',
    access: 'canConsole',
    routes: [
      {
        path: '/records',
        redirect: '/records/alarms',
      },
      {
        name: 'alarms',
        icon: 'alert',
        path: '/records/alarms',
        component: './console/alarms',
      },
      {
        name: 'stock-in',
        icon: 'download',
        path: '/records/stock-in',
        component: './console/oil-in-out',
      },
      {
        name: 'stock-out',
        icon: 'upload',
        path: '/records/stock-out',
        component: './console/oil-in-out',
      },
    ],
  },
  {
    // 资产管理：客户 → 油站 → 设备三级，按层级排列。与「设备监控」并列的顶层菜单，URL 是 /assets/*
    // （原因同记录查询：不能放在 /console/ 下）。它不在 /ops 下，所以客户管理员不会碰到 /ops 的拦截。
    // 整组对平台管理员和客户管理员开放，客户项只对平台管理员。
    path: '/assets',
    name: 'assets',
    icon: 'database',
    access: 'canManageAssets',
    routes: [
      {
        // 落到第一个可见项：平台管理员 → 客户，客户管理员 → 油站（按角色分流，见 pages/console/assets/index.tsx）
        path: '/assets',
        component: './console/assets',
      },
      {
        name: 'customers',
        icon: 'shop',
        path: '/assets/customers',
        component: './console/assets/customers',
        access: 'canPlatformAdmin',
      },
      {
        // 客户开通向导（#14）：从客户页进入，不进菜单
        path: '/assets/customers/:id/onboarding',
        component: './console/assets/customers/onboarding',
        access: 'canPlatformAdmin',
      },
      {
        name: 'stations',
        icon: 'environment',
        path: '/assets/stations',
        component: './console/assets/stations',
      },
      {
        name: 'devices',
        icon: 'cluster',
        path: '/assets/devices',
        component: './console/assets/devices',
      },
      {
        // 员工按罐授权（#17）：云端 2026-10-14 起仅平台管理员可读写授权（客户管理员 403），入口随之收窄
        name: 'staff',
        icon: 'team',
        access: 'canPlatformAdmin',
        path: '/assets/staff',
        component: './console/assets/staff',
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
