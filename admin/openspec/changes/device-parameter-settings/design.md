## Context

- 云端契约：`cloud/docs/config-command-contract.md`（提交保护）、`cloud/docs/tank-settings-contract.md`（字段、校验、镜像）。
- 后台已有同类流程：`PurgeDeviceModal`（异步任务轮询、超时找回、不自动重发），本变更沿用其做法。
- 设备详情页签按设备编号 `key`，换设备整体重建；迟到响应按请求序号/取消标志丢弃。

## Decisions

### D1 页签而不是独立页面
参数与操作记录挂在设备详情下，复用设备上下文（油站、换机跟随）。`/console/issue-command` 只做入口：列出可见设备，点进
`?tab=settings`。

### D2 requestId 的生命周期
打开确认弹窗时生成一次（`adm-<时间>-<随机>`）。同一次确认的重试（含超时后"用同一编号重试"）复用；用户关掉弹窗改表单再确认就换新的。
不在任何情况下自动换新编号重发。

### D3 提交前双重核对油站
表单打开时记下设备的 `station_id`（缺字段 = 旧版云端，不允许提交）。点"确认下发"时先重读 `GET /api/devices`：油站变了或设备不可见就
停止并提示刷新；再把**表单打开时**的值作为 `expectedStationId` 发给云端，由云端事务裁决。

### D4 命令跟踪
- `202`/`200` 后保存命令，按 2 秒轮询 `GET .../commands/:commandId`，以 `terminal` 停止；页面隐藏时不发请求。
- `succeeded` 后按 5 秒读 `tank-settings`（或 `device-settings`），`reportedAt` 不早于命令 `issuedAt` 且提交字段全部相等 → 已一致；
  新鲜但不等 → 不一致（可能被现场覆盖）；3 分钟内都不新鲜 → 提示仍未回报，可手动刷新。
- 超时（status 0 / `command_delivery_unconfirmed`）：先 `?requestId=` 找回；找到就跟踪；找不到显示"结果未确认"，只给"用同一编号重试"。

### D5 字段与预检
字段表在 `utils/deviceConfig.ts`，范围与云端 `config_args.go` 一致（云端仍是裁决者）；顺序预检用"当前值 + 改动"合并后的四级液位，
0 视为不启用。超过罐高只提示、不拦（云端/设备裁决）。油品下拉 0–31 用本地名称、32–39 显示"自定义 N"（设备原文仅当前值可见）。

### D6 错误
按 `code` 分支：`config_commands_disabled`（看 `scope`）、`device_station_changed`、`config_command_in_flight`（跳到在途命令）、
`request_id_reused`、`invalid_args`（显示 `fields`）、`command_delivery_unconfirmed`。账号失效沿用 `toApiError` 的全局处理。不显示云端 `error` 原文。

## Non-Goals
动作类命令、密度编辑、罐表/探针写入、批量下发、WebSocket 推送。
