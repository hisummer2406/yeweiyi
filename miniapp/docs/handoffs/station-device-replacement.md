# miniapp#6 交接：油站唯一大屏、换机跟随

契约：`yeweiyi-cloud/docs/station-device-replacement-contract.md` v1.3（SHA256 bb1cb5ba0186fddc2654e3f232d8f00da5c85fc69efe522cda808576a0fdfc93）。
基线分支 `feat/wechat-phone-login`。本分支只含 #6；罐级授权与扫码只定位（cloud#63）在 miniapp#7，不在此分支。

## 行为
- 首页：按 `station_id` 分组，数据调用只用 `device_name`；罐身份 = `device_name` + 罐号（`tankKey`）；记住的油站已无可见设备时提示联系客户管理员。
- 详情页（§8.1）：从油站入口进入（带 `station` 参数）时跟随油站当前设备。换机 A→B：清空 A 的最新值/罐/报警/出入库/命令/实时横幅/翻页，关闭 A 的 socket 与命令轮询，A 的在途请求与推送按代际丢弃，为 B 重建查询与订阅；B 无数据显示空状态；B 对当前用户不可见则提示，不回退 A。
- 独立入口（我的记录，仅按设备名）不跟随，也不把 A 显示为油站当前设备。
- 设备级 403（`not authorized for this device`）提示联系管理员并重读关联，不退出登录；仅裸 `not authorized` 结束会话（`request.ts` 未改）。

## 本分支未包含（在 miniapp#7）
- `POST /api/devices/resolve` 扫码只定位、移除 bind、`granted_tanks`、员工隐藏控制命令 tab。本分支扫码仍走 bind，需云端 #63 部署前先于 miniapp#7 保持兼容。

## 检查（本分支实际执行）
- `npm run typecheck`：通过。
- `npm run check`：stations / recordsRange / loginFlow / stationDevice（10 项）通过。
- 页面级逻辑（代际守卫、switchDevice）无自动化测试，仅 typecheck 覆盖。

## 未验证
- WeChat DevTools / 真机；换机后油站入口自动切 B、B 空状态、A 独立入口、撤销后已打开页面不再更新、ws 换机瞬间行为。
- 与真实云端的小程序级联调（API 级已在隔离开发实例验证 T0 换机/授权/撤销）。
