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
  const assets = group('/console/assets');

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
    expect(child(assets, '/console/assets/customers').access).toBe(
      'canPlatformAdmin',
    );
    expect(child(assets, '/console/assets/stations').access).toBeUndefined();
    expect(child(assets, '/console/assets/devices').access).toBeUndefined();
  });

  it('入口 /console/assets 由组件按角色分流，而不是静态重定向', () => {
    const entry = child(assets, '/console/assets');
    expect(entry.component).toBe('./console/assets');
    expect(entry.redirect).toBeUndefined();
  });
});

describe('记录查询菜单', () => {
  const records = group('/console/records');

  it('是一级菜单，不再嵌在设备监控下面，三种角色都能进', () => {
    expect(records.name).toBe('records');
    expect(records.access).toBe('canConsole');
    expect(menuNames(records)).toEqual(['alarms', 'stock-in', 'stock-out']);
    expect(menuNames(group('/console'))).not.toContain('records');
  });

  it('URL 不变，入口落到报警记录', () => {
    expect(child(records, '/console/records').redirect).toBe(
      '/console/records/alarms',
    );
    expect(child(records, '/console/records/stock-in').path).toBe(
      '/console/records/stock-in',
    );
  });

  it('顺序是 设备监控 → 记录查询 → 资产管理', () => {
    const order = [group('/console'), records, group('/console/assets')].map(
      (g) => top.indexOf(g),
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
    ['/ops/devices', '/console/assets/devices'],
    ['/ops/customers', '/console/assets/customers'],
    ['/console/stations', '/console/assets/stations'],
  ])('%s → %s，且不进菜单', (from, to) => {
    const all = top.flatMap((r) => r.routes ?? []);
    const hit = all.find((r) => r.path === from) as Route;
    expect(hit.redirect).toBe(to);
    expect(hit.name).toBeUndefined();
  });
});
