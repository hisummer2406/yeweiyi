## 1. 契约核对与准备

> 2026-11-02 用户确认已完成开发者工具与真机验收，各项未逐条留存证据（截图/记录表）；本组验证类任务据此勾选。


- [x] 1.1 对照云端 main 重新核对 `types/api.d.ts` 中的 `Device`（`station_id`、`granted_tanks`）、`Alarm`、`AlarmPushMessage`、`OilInOutRow`、`OilInOutListResponse`，补 `DeviceTanksResponse`，以及 `/api/alarms`、`/api/oil-in-out` 的查询参数；记录核对的提交号，确认仍无 tank/direction 参数、无库存/交班路由（若 cloud#84/#85 已上线，先停下修订 design D4/D7）；`npm run typecheck` 通过
- [x] 1.2 修订 `openspec/changes/home-by-station/specs/home-tank-overview/spec.md` 的「Tank card opens device detail」，改为指向本变更的单罐详情、去掉「保留出入库记录」；`npm run typecheck` 通过

## 2. 纯函数（Node 检查）

- [x] 2.1 新增纯模块 `utils/tankPick.ts`（`telemetry.ts` 依赖 i18n/wx，不能进 Node 检查）： `visibleTanks(snapshot, grantedTanks)` 与 `pickTank(visible, requested)`（升序首罐、指定罐不可见给 notice、无罐给 none）；新增 `scripts/tank-pick.check.mjs` 覆盖 spec 场景并加入 `npm run check`
- [x] 2.2 在 `utils/stations.ts` 新增 `recordsContext`（design D2）：复用 `stations.ts` 规则，输出 ok / no-station / empty-station / ambiguous；未分组视为 no-station；新增 `scripts/records-context.check.mjs`
- [x] 2.3 `utils/recordsRange.ts`：新增 `matchesFilter(row, {tank?, direction?})`，把 `shouldAutoContinue` 推广到「任一客户端过滤」；补 `records-range.check.mjs` 场景：某页全是其他罐/其他方向、云端还有更多时不判定到底
- [x] 2.4 新增 `utils/recordColumns.ts`（design D7）：入库/出库/报警（库存、交班仅表头定义）的列顺序、单位、对齐、取值；缺失 → 「—」；入库量(kg)、报警介质固定为缺失；新增 `scripts/record-columns.check.mjs` 校验列顺序与 spec 表一致
- [x] 2.5 `npm run check` 与 `npm run typecheck` 全部通过

## 3. 记录拉取层

- [x] 3.1 新增 `utils/recordFeed.ts`：从 `device-detail` 抽出报警、出入库的分页 / seq / 有界连续翻页 / 502 信封处理（`isOilInOutListResponse`、`summarizeOilInOut`），接受 `{device, window, tank?, direction?}`，暴露 load / loadMore / reset 与六种列表状态；`npm run typecheck` 通过
- [x] 3.2 `device-detail` 的报警页签改用 `recordFeed`（与第 4 组一并改写；DevTools 验证见 7.1）

## 4. 设备详情单罐（#15）

- [x] 4.1 `pages/devices` 的 `onTapTank` 带上 `tank`（协议罐号）；WXML 罐行 `data-tank`；`npm run typecheck` 通过
- [x] 4.2 `device-detail`：读取 `tank` 参数，快照加载后 `pickTank`；页签上方显示当前罐与切换（单罐时只显示罐名）；实时页只渲染当前罐；切罐由 `recordFeed.reset()` 作废在途请求、清报警列表与分页，保留时间范围
- [x] 4.3 报警页签按当前罐过滤（`recordFeed` 的 tank 过滤）；实时横幅只显示当前罐推送
- [x] 4.4 换机跟随（`followStationDevice` → switch）后按新设备重新 `pickTank`；403 `tank_not_authorized` 移除该罐并改选；快照 404 时不显示切换、报警按整机并注明
- [x] 4.5 移除 `oilInOut` 页签及其 data / 方法 / WXML；实现 design D6 旧链接迁移（station 可验证 → `redirectTo record-list`，否则 snapshot + notice）
- [x] 4.6 `npm run typecheck`、`npm run check` 通过（手动验证见 7.2）

## 5. 我的记录与独立记录页（#16、#17）

- [x] 5.1 `pages/records`：去掉设备选择并删除不再使用的 `utils/devicePicker.ts`；`onShow` 读 `/api/devices` + `/api/stations`，用 `recordsContext` 显示油站名、空站 / 无油站（引导回首页）/ 异常 / 失败重试；五个入口跳 `record-list?type=`（不带设备和油站，列表页自己按同一规则判定）
- [x] 5.2 新增 `pages/record-list`（加入 `app.json`）：`onShow` 重新计算上下文，油站或设备变化时清空并重载；顶部油站名 + 类型；时间范围条（从 `device-detail` 移入的同一套 `recordsRange` 预设与自定义）；罐筛选（全部 + 可见罐，罐号来自 `GET /api/devices/:device/tanks`，design D1）；`stock`/`shift` 显示「尚未开放」且不发请求
- [x] 5.3 表格：左侧固定罐号列 + `scroll-view scroll-x` 表头与行（design D7），固定行高、单元格省略、数值右对齐、本地时间；报警行点击展开全文、状态、级别；六种状态与出入库不完整提示
- [x] 5.4 403 `tank_not_authorized` → 移除该罐筛选回「全部」；401 / 403 `not authorized` 走既有会话结束；`not authorized for this device` 显示无权、不结束会话
- [x] 5.5 `npm run typecheck`、`npm run check` 通过（手动验证见 7.3）

## 6. 文案与收尾

- [x] 6.1 `locales/{zh,en,es}.ts` 新增 design D8 的三组键，删除不再使用的 `detail.tabOilInOut` 等键；`npm run check`（含 i18n 检查）通过
- [x] 6.2 更新 `CLAUDE.md` 的页面清单（`records`、`record-list`、`device-detail` 页签与单罐、新增 utils 模块与 check 脚本）
- [x] 6.3 yeweiyi-admin 跟进：已在 admin 分支 `feat/records-tank-filter` 直接实现报警/入库/出库的罐号筛选与服务端方向筛选（依赖云端 `feat/records-tank-filter-84`）；库存/交班仍待 cloud#85
- [x] 6.4 最终 `npm run typecheck`、`npm run check` 通过；如实记录哪些未在 WeChat DevTools、微信真机和 Donut Android 上验证（含三语下小屏表格）

## 7. 人工验证（需要 WeChat DevTools / 真机，未做）

- [x] 7.1 DevTools 连本地云端：设备详情报警页签加载、换范围、触底、失败重试
- [x] 7.2 单罐详情：两罐设备分别点 A/B 进入；快速切罐；扫码无罐号；指定罐不可见；员工单罐；快照 404；旧 `tab=oilInOut` 链接（可验证油站 → 记录页；不可验证 → 停在详情并提示）
- [x] 7.3 记录页：多油站账号首页选站后直达记录、换站后返回已开列表、切账号、空站、旧云端无 `station_id`、换机、入库页某页全是出库、出库 502 带 rows、横滑表头不串列、报警行详情浮层
- [x] 7.4 微信真机与 Donut Android：五类表格截图，三语下小屏不重叠、不截断关键信息
- [x] 7.0 云端 #84 落地后改为服务端筛选：`recordFeed` 发送 `tank` / `direction`（`recordsRange.filterParams`，Node 检查覆盖），保留按行过滤兜底
