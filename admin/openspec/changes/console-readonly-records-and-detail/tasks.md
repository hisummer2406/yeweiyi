## 1. 文案与取数层

- [x] 1.1 新增 `src/utils/labels.ts`：报警类型/级别/状态、出入库方向与来源映射（键与小程序一致），`label()` 未知值回退原值、空值显示 `--`；单测覆盖已知、未知、空值
- [x] 1.2 在 `typings.d.ts` 增加 `Alarm`、`StockEvent`、`StockListResult`、记录查询参数等类型（字段读自云端 `alarmResponse`、`stockEventResponse`、`stockListResponse`）
- [x] 1.3 新增 `src/services/ant-design-pro/alarms.ts` 的 `listAlarms`：带 `device/beginTime/endTime/sort=false/limit=500`，用 `getResponse` 读 `X-Records-Partial`，失败经 `toApiError`；单测覆盖参数、响应头、401/403 转交、其他错误
- [x] 1.4 新增 `src/services/ant-design-pro/stock.ts` 的 `listStockEvents`：200 与「502 且 body 带 `code`」都返回结果对象（502 填 `degraded`），其他状态抛 `ApiError`；单测覆盖 200、502 带 rows、502 空 rows、500、401
- [x] 1.5 `src/services/ant-design-pro/index.ts` 导出新模块

## 2. 降级提示与查询状态

- [x] 2.1 新增 `summarizeAlarms`、`summarizeStock` 纯函数（`Notice[]`）：partial、legacySkipped、invalidSource、folded、unparsable/502、截断（满 500 或 `truncated`）；单测覆盖每一种及「没有任何提示才算真空」
- [x] 2.2 新增 `useRecordsQuery` hook：滚动默认窗口与固定自选窗口、请求序号丢弃旧响应、30 秒轮询、手动刷新、加载/失败/重试、卸载清理；单测（fake timers）覆盖轮询、旧响应丢弃、窗口变化重置分页
- [x] 2.3 文案加入 `src/locales/zh-CN/pages.ts`、`en-US/pages.ts`（`pages.console.*`）

## 3. 共享组件

- [x] 3.1 从 `src/pages/console/devices/index.tsx` 抽出 `TankCard` 到 `src/pages/console/components/TankCard.tsx`，总览改用它；补测试确认点击仍跳转详情、异常标签仍显示，总览行为不变
- [x] 3.2 `DevicePicker`：读 `GET /api/devices`，`?device=` 直达并同步，URL 设备不可见时不请求并提示，空列表显示「还没有绑定设备」，失败可重试
- [x] 3.3 `AlarmRecords`：时间范围选择器、表格（时间/油罐/类型/级别/状态/说明/数值）、前端分页、提示渲染、空状态、失败重试、鉴权失败静默、固定的「转移前记录不显示」说明
- [x] 3.4 `StockRecords`：同上，加方向过滤（`direction` 缺省显示两个方向）、来源文案、降级提示、502 带 rows 时同时显示行
- [x] 3.5 组件测试：排序/窗口参数、截断提示、partial、legacySkipped、502 空 rows 不显示空状态、502 带 rows、方向过滤、旧响应丢弃、鉴权失败静默、失败不显示成空表

## 4. 页面与路由

- [x] 4.1 报警记录页 `src/pages/console/alarms/index.tsx`：`DevicePicker` + `AlarmRecords`
- [x] 4.2 入库/出库页 `src/pages/console/oil-in-out/index.tsx`：路径决定方向，`DevicePicker` + `StockRecords`
- [x] 4.3 设备详情页 `src/pages/console/device-detail/index.tsx`：三个页签、`?tab=` 同步、非法回退、设备名解码、仅挂载活动页签；实时数据页签含「从未上报」「无权查看（403）」「失败重试」、30 秒刷新
- [x] 4.4 页面级测试：页签切换更新 URL、非法 tab 回退、特殊字符设备名请求路径正确转义、非活动页签不发请求

## 5. 验证与收尾

- [x] 5.1 对新增行为做变异检查（去掉 `sort=false`、去掉截断提示、502 当通用错误、partial 被忽略、方向过滤失效、旧响应不丢弃、页签全部挂载），确认测试拦得住，没拦住的补测试
- [x] 5.2 用平台管理员账号对真实后端（含 `origin/main`）用 curl 验证：设备列表、`/api/alarms` 与 `/api/oil-in-out` 的参数与响应形状、空窗口、`X-Records-Partial` 头、无设备/无权限的状态码；如实记录联调看不到历史数据的限制
- [x] 5.3 更新 `CLAUDE.md` 状态与目录说明；`npm run lint`、`npm test` 通过并如实报告结果（`npm run build` 是否运行如实说明）
- [ ] 5.4 浏览器验证页面（需要 Chrome 扩展连上）；未验证则在 PR 里明确写「未在浏览器中验证」
