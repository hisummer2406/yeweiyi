## Context

已对照 yeweiyi-cloud main `c34316b`（2026-10-26）：`internal/api/router.go`、`listparams.go`（只解析 device / beginTime / endTime / limit / offset / sort，没有 tank、direction 参数）、`alarms.go`（裸数组，无「还有更多」标志）、`stock.go`（信封带 `truncated`）、`router.go` 中没有交班、库存路由。cloud#84（按罐筛选）、cloud#85（库存/交班与五类字段口径）均为 OPEN、未实现。

本仓库现状：
- `pages/devices` 的 `onTapTank` 只带 `device` 和 `station`，不带罐号。
- `pages/device-detail`（909 行）有 snapshot / alarms / oilInOut 三个页签（commands 已注释关闭），快照循环显示整机所有罐；报警与出入库共享时间范围条与 `onReachBottom`；`fetchStock` 已有「按方向在前端过滤 + 有界连续翻页」的做法（`shouldAutoContinue`、`MAX_AUTO_PAGES`）；`deviceGen` / 各 `*Seq` 用于丢弃迟到响应；`followStationDevice` 处理换机。
- `pages/records` 遇多设备弹 `wx.showActionSheet` 选设备，然后跳详情的 alarms/oilInOut 页签。
- 首页当前油站存于 `utils/stationPref.ts`（按 `userKeyFromToken` 隔离），选站规则在 `utils/stations.ts`（`buildStationOptions`、`pickStation`、`devicesOfStation`）。

## Goals / Non-Goals

**Goals:**
- 详情一次只看一个罐，入口带罐号；记录页按首页当前油站绑定唯一设备；五类记录独立页面、表格化。
- 不等云端也能交付：现有接口 + 客户端连续翻页过滤，结果正确（不把「本页没有」当「没有」）。
- 云端 #84 / #85 上线后只需替换数据获取层，不重做页面。

**Non-Goals:**
- 库存、交班的数据接入与字段设计；报警介质、出入库千克的取值口径。
- 控制命令、配置下发；yeweiyi-admin 改动；首页视觉（#13/#14）。

## Decisions

### D1 罐身份 = (device_name, 协议罐号)
沿用 `utils/stationDevice.ts` 的 `tankKey`。入口参数为 `device`、`tank`（协议罐号）、`station`。罐号来源：实时快照中的罐（`toTankViews`）；customer_user 再与设备行的 `granted_tanks` 取交集作为保险（云端已过滤，这只是防御）。快照 404 时没有罐列表——详情只能按整机。记录页（没有快照可用）改读 `GET /api/devices/:device/tanks`（`internal/api/tankgrants.go` `listDeviceTanksHandler`，2026-10-26 核对 main `c34316b`）：云端同样从最新快照取罐号，员工已按授权过滤，从未上报时 200 `reported:false`、`tanks:[]`，与详情页的来源一致。
*备选*：用列表下标——拒绝，换机或授权变化后下标会指向别的罐。

### D2 记录页的油站上下文抽成纯函数
在 `utils/stations.ts` 新增 `recordsContext`（纯函数，Node 检查）：输入 `devices`、`stations`、记住的 stationKey，输出 `{kind:'ok', station, device}` / `{kind:'no-station'}` / `{kind:'empty-station', station}` / `{kind:'ambiguous', station}`。复用 `buildStationOptions`、`pickStation`、`devicesOfStation`，与首页同一套规则，避免两处选站逻辑漂移。「未分组」（`UNGROUPED_KEY`）不是有效油站，返回 `no-station`。页面每次 `onShow` 重新读 `/api/devices` + `/api/stations` 再算一遍。
*备选*：首页把当前设备写进 globalData——拒绝，重启后丢失、也无法发现换机。

### D3 新页面 `pages/record-list`，`type` 参数区分五类
`record-list?type=in|out|alarm|stock|shift`。设备与油站都由 D2 在每次 `onShow` 按首页当前油站算出，参数里不放设备码，避免旧设备被带入；首页换站后返回已打开的列表即跟随新站。只有 D6 验证过的旧链接带 `station=<id>`，此时页面固定在该油站（不再跟随首页，该油站失效则提示选站、不回退）。`stock`、`shift` 直接渲染「尚未开放」，不发请求。`records` 页只负责显示油站名与五个入口。
*备选*：五个页面——拒绝，逻辑 90% 相同。

### D4 记录拉取抽成 `utils/recordFeed.ts`，过滤判定留在 `recordsRange.ts`
把 `device-detail` 里的 `fetchStock` / `loadAlarms` / 分页 / seq 模式抽成一个与页面无关的 feed（依赖 `request`，不进 Node 检查），供 `record-list` 和详情报警页签共用。纯判定扩展到 `recordsRange.ts`：`matchesFilter(row, {tank?, direction?})`、以及把现有 `shouldAutoContinue` 推广为「有任一客户端过滤（方向或罐）就用 `FILTERED_PAGE_SIZE` 并有界连续翻页」。每次滚动到底最多自动翻 `MAX_AUTO_PAGES` 页；仍不够且云端还有更多时，页脚显示「继续加载」而不是「已到底」或「暂无」。
**2026-10-26 更新**：云端已在分支 `yeweiyi-cloud` `feat/records-tank-filter-84` 实现 `tank`（报警、出入库）与 `direction`（出入库）筛选，先筛选后分页，契约 `docs/records-filter-contract.md` v1.0（未合并 main）。feed 现在把二者作为查询参数发出（`filterParams`），同时保留按行过滤与有界连续翻页作为旧云端兜底：旧云端忽略未知参数、返回整机数据，按行过滤保证不混入别的罐/方向。

### D5 详情单罐
- 页签只剩 snapshot、alarms（commands 维持注释）。新增 `currentTank` 与 `tankGen`；切罐时 `tankGen++`、清报警列表与分页，时间范围保留。
- 快照仍请求整机 `telemetry/latest`（云端只返回授权罐），页面只渲染当前罐。
- 实时报警横幅过滤 `msg.tank === currentTank`；非当前罐的推送不显示，但标记该罐报警列表需要重载。
- 选罐规则做成纯函数 `pickTank(visibleTanks, requested)` → `{tank, notice?}`，放 `utils/telemetry.ts`，Node 检查。
- 换机（`followStationDevice` 返回 switch）时 `currentTank` 按新设备重新 `pickTank(…, undefined)`，不沿用旧罐号。

### D6 旧 `tab=oilInOut` 链接
`device-detail` 的 `onLoad` 看到 `tab=oilInOut`：带 `station` 时先读 `/api/devices`，`followStationDevice` 返回 `same` 则 `redirectTo record-list?type=in|out&station=`；其余情况留在 snapshot 并设 `notice`。这些链接只可能来自旧版 `records` 页与分享，量很小，但不能跳到别的站。

### D7 表格实现
无组件库：左侧固定列（罐号）是普通 `view` 列，右侧为 `scroll-view scroll-x`，内含表头与数据行，因此表头与数据天然同步横滑；纵向滚动交给页面，`onReachBottom` 照旧。两侧行高固定、单元格单行省略，保证左右对齐；报警行点击在页内展开详情层（不用 `wx.showModal`）。列定义（key、表头 i18n key、单位、对齐、取值函数）放 `utils/recordColumns.ts`，纯函数，Node 检查列顺序与「缺失 → —」。
列与当前云端字段的对应（缺失即「—」）：
- 入库/出库：介质 ← `oilType`；时间 ← `time`；前高/后高 ← `beginLevelMm`/`endLevelMm`；前量/后量 ← `beginVolumeL`/`endVolumeL`；量(L) ← `deliveredVolumeL`；量(kg) ← 无（cloud#85 定口径前为「—」，不用 `endWeightKg - beginWeightKg`）。
- 报警：介质 ← 无（「—」）；报警信息 ← 类型标签 + `text`；时间 ← `time`；状态/级别 ← `status`/`severity`。
字体大小沿用适老化（#13）完成前的现值，不在本变更内定字号。

### D8 i18n
新增 `records.*`（油站头部、空站、无油站、尚未开放）、`table.*`（表头）、`detail.tank*`（罐切换、罐不可见、出入库已迁移）三组键，三语齐全；`scripts/i18n.check.mjs` 会校验。

## Risks / Trade-offs

- [客户端连续翻页过滤请求多、慢（30 天、罐多时尤甚）] → 每次触底有界（`MAX_AUTO_PAGES`），页脚可继续；#84 上线后切服务端筛选。
- [`/api/alarms` 没有「更多」标志，靠 `limit+1` 探测] → 沿用现有 `splitAlarmPage`，过滤后仍以探测结果判断是否到底。
- [固定列方案要求左右行高一致，长文本会被省略] → 单行省略 + 点击查看全文；Android（Donut）需单独看横滑与对齐。
- [移除详情出入库页签是不兼容变更] → D6 迁移旧链接；`records` 页入口同步上线，两者在同一提交中完成。
- [`home-by-station` 变更的 spec 与本变更冲突] → tasks 中修订该条，归档顺序上本变更在其后。

## Migration Plan

单次发布，无数据迁移。回滚即回退代码；本地存储只新增不改键（油站偏好沿用 `station:<userKey>`）。

## Open Questions

- 交班「交班时间 / 接班时间」与设备原记录起止的对应关系——等 cloud#85 / zhongchengyeweiyi#49，本变更不显示交班表格。
  **更新 2026-11-04**：cloud#85 已合并（main `0335ce3`），契约 §5 定为 交班时间 = `endTime`、接班时间 = `beginTime`；交班、库存表格已接入（tasks 7.5）。与设备原记录逐条核对仍待真实记录。
- 入库量(kg)、报警介质的数据来源——cloud#85。
- 快照 404 的设备没有罐号来源（`/tanks` 也来自快照）：记录页此时只有「全部」。是否改用授权表——#84 契约定稿时一并确认。
