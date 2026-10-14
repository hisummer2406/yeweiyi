import { getMatchMenu, transformRoute } from '@umijs/route-utils';
import { matchRoutes } from 'react-router';
import { describe, expect, it } from 'vitest';
import routes from '../config/routes';

type Route = {
  path?: string;
  name?: string;
  access?: string;
  redirect?: string;
  component?: string;
  routes?: Route[];
};

const top = routes as Route[];
const group = (path: string) => top.find((r) => r.path === path) as Route;
const child = (g: Route, path: string) =>
  g.routes?.find((r) => r.path === path) as Route;
const menuNames = (g: Route) =>
  (g.routes ?? []).filter((r) => r.name).map((r) => r.name);

describe('资产管理菜单组', () => {
  const assets = group('/assets');

  it('是与设备监控并列的顶层菜单，整组要求 canManageAssets', () => {
    expect(assets.name).toBe('assets');
    expect(assets.access).toBe('canManageAssets');
    expect(top.indexOf(assets)).toBeGreaterThan(top.indexOf(group('/console')));
  });

  it('按 客户 → 油站 → 设备 → 员工授权 排列，客户项只对平台管理员', () => {
    expect(menuNames(assets)).toEqual([
      'customers',
      'stations',
      'devices',
      'staff',
    ]);
    expect(child(assets, '/assets/customers').access).toBe('canPlatformAdmin');
    expect(child(assets, '/assets/stations').access).toBeUndefined();
    expect(child(assets, '/assets/devices').access).toBeUndefined();
  });

  it('入口 /assets 由组件按角色分流，而不是静态重定向', () => {
    const entry = child(assets, '/assets');
    expect(entry.component).toBe('./console/assets');
    expect(entry.redirect).toBeUndefined();
  });
});

describe('记录查询菜单', () => {
  const records = group('/records');

  it('是一级菜单，不再嵌在设备监控下面，三种角色都能进', () => {
    expect(records.name).toBe('records');
    expect(records.access).toBe('canConsole');
    expect(menuNames(records)).toEqual(['alarms', 'stock-in', 'stock-out']);
    expect(menuNames(group('/console'))).not.toContain('records');
  });

  it('URL 不变，入口落到报警记录', () => {
    expect(child(records, '/records').redirect).toBe('/records/alarms');
    expect(child(records, '/records/stock-in').path).toBe('/records/stock-in');
  });

  it('顺序是 设备监控 → 记录查询 → 资产管理', () => {
    const order = [group('/console'), records, group('/assets')].map((g) =>
      top.indexOf(g),
    );
    expect(order).toEqual([...order].sort((a, b) => a - b));
  });
});

describe('运维管理菜单', () => {
  const ops = group('/ops');

  it('仍仅平台管理员，只剩 账号管理、审计日志', () => {
    expect(ops.access).toBe('canPlatformAdmin');
    expect(menuNames(ops)).toEqual(['users', 'audit']);
  });

  it('默认落点是账号管理', () => {
    expect(child(ops, '/ops').redirect).toBe('/ops/users');
  });
});

describe('旧地址重定向', () => {
  it.each([
    ['/ops/devices', '/assets/devices'],
    ['/ops/customers', '/assets/customers'],
    ['/console/stations', '/assets/stations'],
    ['/console/records', '/records/alarms'],
    ['/console/records/alarms', '/records/alarms'],
    ['/console/records/stock-in', '/records/stock-in'],
    ['/console/records/stock-out', '/records/stock-out'],
    ['/console/assets', '/assets'],
    ['/console/assets/customers', '/assets/customers'],
    ['/console/assets/stations', '/assets/stations'],
    ['/console/assets/devices', '/assets/devices'],
    ['/console/assets/staff', '/assets/staff'],
  ])('%s → %s，且不进菜单', (from, to) => {
    const all = top.flatMap((r) => r.routes ?? []);
    const hit = all.find((r) => r.path === from) as Route;
    expect(hit.redirect).toBe(to);
    expect(hit.name).toBeUndefined();
  });
});

describe('菜单高亮', () => {
  // ProLayout 用 @umijs/route-utils 的 getMatchMenu 算选中项：路径 P 会匹配 P/(.*)，
  // 所以任何分组或无名重定向的 path 都不能是别的分组页面的前缀，否则点别的菜单它（及其父分组）也会亮。
  // 菜单数据走和 ProLayout 相同的 transformRoute（子项选中时父分组靠 pro_layout_parentKeys 亮起）。
  const { menuData } = transformRoute(top as never, false, undefined, true);
  // 只有有名字、未隐藏的项会渲染成菜单项；无名重定向的 key 命中了也点不亮任何东西。
  type Item = {
    key?: string;
    name?: string;
    hideInMenu?: boolean;
    children?: Item[];
  };
  const rendered = new Set<string>();
  const collect = (items: Item[]) => {
    for (const item of items) {
      if (item.name && !item.hideInMenu && item.key) rendered.add(item.key);
      collect(item.children ?? []);
    }
  };
  collect(menuData as Item[]);
  const selected = (pathname: string) => [
    ...new Set(
      getMatchMenu(pathname, menuData, true)
        .map((m) => m.key as string)
        .filter((key) => rendered.has(key)),
    ),
  ];

  it.each([
    ['/records/alarms', ['/records', '/records/alarms']],
    ['/records/stock-in', ['/records', '/records/stock-in']],
    ['/assets/stations', ['/assets', '/assets/stations']],
    ['/assets/devices', ['/assets', '/assets/devices']],
    ['/console/devices', ['/console', '/console/devices']],
    ['/console/devices/ZCT000001', ['/console', '/console/devices']],
    ['/console/issue-command', ['/console', '/console/issue-command']],
    ['/ops/users', ['/ops', '/ops/users']],
  ])('%s 只点亮自己所在的分组', (pathname, expected) => {
    expect(selected(pathname)).toEqual(expected);
  });
});

describe('嵌套绝对路径', () => {
  // react-router 要求嵌套的绝对子路径以全部父路径开头，否则整个应用在加载路由时抛错；
  // 按 umi 的嵌套方式把整张路由表交给 matchRoutes 过一遍。
  const toRR = (rs: Route[]): object[] =>
    rs.map((r) => ({ path: r.path, children: r.routes && toRR(r.routes) }));

  it.each([
    '/console',
    '/console/devices',
    '/console/devices/ZCT000001',
    '/records/alarms',
    '/assets/stations',
    '/assets/stations',
    '/ops/users',
  ])('%s 能匹配到路由', (pathname) => {
    expect(matchRoutes(toRR(top) as never, pathname)).not.toBeNull();
  });
});
