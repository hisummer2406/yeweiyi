## Why

#4（总方案 #3）：后台要能远程改设备参数——读设备实际参数 → 业务表单修改 → 确认下发 → 查看执行结果 → 核对设备回显。云端契约
已由 cloud PR #13（openspec `config-command-delivery`，`cloud/docs/config-command-contract.md`）定稿并实现：配置方法必填
`requestId` / `expectedStationId`、单条命令查询、同目标在途互斥、两级开关、整机设置镜像。现在后台的 `/console/issue-command`
只有一个标题，设备详情没有参数与操作记录。

产品范围（2026-11-08）：本期只做配置类；动作类（消音/清警/重启/泵阀）不提供入口；密度只读。

## What Changes

- 设备详情新增两个页签（仅管理员，后台本来就只有管理员）：**参数设置**（`?tab=settings`）、**操作记录**（`?tab=commands`）。
- 参数设置：
  - 显示该设备远程配置是否开放；平台管理员可开放/关闭（必填原因）。
  - 按罐显示设备上报的当前配置与上报时间；未上报、读取失败与 0 / null 分别表达。
  - 业务表单只开放云端白名单字段（四级液位报警、高水位、高温、三项修正、油品、容积）；罐高、罐型、传感器长度、密度、低温报警只读。
  - 只提交改动的字段；本地先做范围与顺序预检；确认弹窗显示设备、油站、罐号、修改前后与单位，原因必填。
  - 提交前重新读设备列表核对油站；带 `expectedStationId` 与本次确认生成的 `requestId`。
  - 跟踪命令：按 `terminal` 轮询；`succeeded` 后读回镜像，区分"等待设备回报 / 已一致 / 不一致"；超时按 `requestId` 找回，
    找不到只允许用**同一** `requestId` 重试；`expired` 显示"结果无法确认"。页面隐藏时暂停轮询。
  - 整机设置（出入库判定阈值）：所有管理员可看，只有平台管理员可改。
  - 标定表只读查看。
- 操作记录：分页列出该设备命令（时间、方法、对象、参数、操作人、原因、状态、错误/结果）。
- `/console/issue-command`（菜单"远程控制"）改为设备列表入口，跳到设备详情的参数设置页签。

## Capabilities

### New Capabilities
- `console-device-settings`：设备参数设置、整机设置、远程配置开关、操作记录与远程控制入口。

## Impact

- 新请求函数 `src/services/ant-design-pro/deviceConfig.ts`；`ApiError` 增加 `data`（冲突响应里的 `scope`/`currentStationId`/`commandId`）。
- 纯函数 `src/utils/deviceConfig.ts`（字段表、差异、预检、命令阶段、回显判定、错误归类）。
- 页面：`src/pages/console/device-detail/{SettingsTab,CommandsTab,...}`、`src/pages/console/issue-command`。
- 文案：`src/locales/{zh-CN,en-US}/pages.ts`。
- 依赖 cloud PR #13 部署；没有它时配置提交会得到旧版 400/409，页面按错误提示处理。
