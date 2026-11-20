## 1. 首页

- [x] 1.1 `pages/devices`：`onShow` 启动 5 秒定时器，`onHide`/`onUnload` 停止；定时器静默重读各设备的 `telemetry/latest`
- [x] 1.2 静默刷新不显示加载态，失败沿用现有的保留旧读数和 `staleText` 逻辑
- [x] 1.3 每台设备在上一次请求未返回时跳过本次

## 2. 详情页

- [x] 2.1 `pages/device-detail`：当前 tab 为 `snapshot` 且页面可见时运行 5 秒定时器；切 tab、`onHide`、`onUnload` 时停止
- [x] 2.2 同 1.2、1.3（静默刷新不重读罐形；403 仍提示一次，随后停止刷新并重新解析站点设备）

## 3. 验证

- [x] 3.1 `npm run typecheck` 与 `npm run check` 通过（2026-11-18）
- [ ] 3.2 开发者工具里停在首页和详情页，看网络面板每 5 秒一次请求；切后台、切 tab 后请求停止
- [ ] 3.3 配合安卓端新 APK，在 ZCT000001 上改变液位，手机在 6 秒内显示新读数
