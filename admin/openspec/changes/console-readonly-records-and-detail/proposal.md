## Why

`/console/*` 是小程序的 PC 版，目前只有设备总览有内容；设备详情、报警记录、入库/出库记录仍是占位页，客户在 PC 上看不到
设备的报警和出入库历史。这些接口在 `yeweiyi-cloud` 已就绪（`GET /api/alarms`、`GET /api/oil-in-out`、
`GET /api/devices/:device/telemetry/latest`），且都是只读、风险低；远程控制涉及物理动作，单独一轮做。

## What Changes

- 设备详情 `/console/devices/:deviceName`：三个页签 实时数据 / 报警记录 / 出入库记录（`?tab=` 切换，与小程序设备详情的前三个页签对齐）。
  实时数据复用设备总览的油罐卡片（从总览页抽成共享组件）。
- 报警记录 `/console/records/alarms`、入库记录 `/console/records/stock-in`、出库记录 `/console/records/stock-out`：
  页面顶部选择设备（`GET /api/devices`，支持 `?device=` 直达），下方是记录表。
- 两个共用组件 `AlarmRecords`、`StockRecords`（设备作为参数）同时被记录页和设备详情页签使用。
- 取数规则：云端接口必须带 `device`，默认窗口是最近 24 小时且**默认升序**，前端显式传 `sort=false`；一次取窗口内最新 500 条，
  前端分页；时间窗可用范围选择器修改。入库/出库按路由方向在前端过滤（云端无方向过滤）。
- **如实展示降级**，不把不完整显示成「没有记录」：报警的 `X-Records-Partial` 头与出入库的 `partial` 提示记录可能不完整；
  `legacySkipped > 0` 提示有旧记录无法显示；`unparsable > 0` 或 502 `STOCK_EVENT_FORMAT_UNSUPPORTED` 以醒目错误提示
  「设备固件尚未升级」，不显示成空表；报警满 500 条或出入库 `truncated` 提示缩小时间范围。
- 固定提示：设备转移给当前客户之前的记录不会显示（云端查询窗口的下界是设备归属当前客户的时间）。
- 文案映射（报警类型/级别/状态、出入库方向）对齐小程序 `utils/labels.ts`，未知值显示原值而不是空白。
- 自动刷新沿用总览的 30 秒轮询。
- **不做**：历史趋势图（前端未装图表库，图表形式待产品定）、远程控制与指令列表、WebSocket 报警实时推送、交班记录、库存记录。

## Capabilities

### New Capabilities
- `console-device-records`: 客户在 PC 控制台查看某台设备的报警记录与出入库记录——设备选择、时间窗、分页、降级与截断的如实提示。
- `console-device-detail`: 设备详情页的页签结构与实时数据展示，复用共享的油罐卡片和记录组件。

### Modified Capabilities
<!-- 无：openspec/specs/ 里没有 /console 相关的现有 spec -->

## Impact

- 代码：`src/pages/console/{alarms,oil-in-out,device-detail}/`、`src/pages/console/devices/` 抽出共享油罐卡片、新增
  `src/services/ant-design-pro/{alarms,stock}.ts` 与类型、`src/utils/labels.ts`，及对应测试；`CLAUDE.md` 状态说明。
- 后端：**不需要改动**。依赖 `origin/main` 上已有的 `GET /api/alarms`、`GET /api/oil-in-out`、`GET /api/devices`、
  `GET /api/devices/:device/telemetry/latest`。
- 依赖：不新增 npm 依赖。
- 联调限制（如实记录）：此前清空本地库后设备的归属时间被重置，云端按归属时间截断查询窗口，旧记录大概率不可见，
  本地联调多半只能验证空窗口和接口形状，不能验证历史数据的展示。
- 客户账号需要有已绑定的设备才有数据；扫码绑定闭环（授权规则待确认）不在本变更内。联调使用平台管理员账号（看得到全部设备）。
