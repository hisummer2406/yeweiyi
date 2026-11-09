## Why

#6（总方案 #3）：小程序的「储罐设置」只存本机草稿、「参数修正」只存本机、「补录记录」提示即将上线、「发送控制命令」是旧的通用 JSON 表单。
云端已在 PR #13 定稿远程配置的提交保护（`cloud/docs/config-command-contract.md`），补录接口（#69，`cloud/docs/stock-manual-entry-contract.md`）早已上线。
用户 2026-11-08 决定补齐控制模块：本期只做配置类，密度只读，开关按设备白名单。

## What Changes

- **储罐设置**：上方显示「设备当前回报」（`tank-settings` 镜像 + 上报时间），与「本机草稿」分开；草稿只能由用户点「导入设备值」明确导入。
  管理员（客户管理员、平台管理员）在设备已开放时可「下发到设备」：只发与设备回报不同且开放的字段（四级液位、高水位、高温、容积）；
  密度、罐高、低水位、低温、物料颜色、介质文本不下发。
- **参数修正**：改为当前油站的设备与可见罐，显示设备当前修正值，「保存到控制器」走同一下发流程（三项修正）。
- **补录记录**：接入 `POST /api/devices/:device/stock-events`（管理员），带时间、原因与 `requestId`。
- **操作记录**（原「发送控制命令」页）：显示本次命令进度（按 `terminal` 轮询，成功后读回镜像判定回显）与该设备最近命令；不再提供通用 JSON 发送。
- 下发流程：表单内填写原因；确认框列出修改前后；确认时重读设备列表（`commandGuard`），油站变了就停止；带本次确认的 `requestId` 与
  `expectedStationId`；超时先按 `requestId` 找回，找不到只允许同编号重试。员工（`customer_user`）不显示任何下发入口。
- 三种语言同步。

## Capabilities

### New Capabilities
- `remote-config`：小程序的远程配置下发、命令进度与回显、补录。

## Impact

- 新纯函数 `utils/remoteConfig.ts`（Node 校验 `scripts/remote-config.check.mjs`），请求封装 `utils/remoteConfigApi.ts`。
- 页面：`tank-config`、`calibration`、`backfill`、`issue-command`；`device-detail` 不变（命令页签仍关闭）。
- `types/api.d.ts`、`locales/{zh,en,es}.ts`、`CLAUDE.md`。
- 依赖 cloud PR #13 部署；旧云端的设备行没有 `config_commands_enabled`，页面不提供下发。
