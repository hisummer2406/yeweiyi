## Context

`/console/*` 目前只有设备总览有内容：`src/pages/console/devices/index.tsx` 对 `GET /api/devices` 的每台设备并行取最新遥测，
30 秒轮询，点油罐卡片跳 `/console/devices/:deviceName`。设备详情、三个记录页是占位。云端接口已就绪，事实如下（读自 `yeweiyi-cloud` `origin/main`）：

- `GET /api/alarms?device=`：响应是**裸数组**；`device` 必填；默认窗口最近 24 小时、**默认升序**；`limit` 1-500；`beginTime`/`endTime` 为 RFC3339；
  首次回填未完成时带响应头 `X-Records-Partial: true`；没有总数。
- `GET /api/oil-in-out?device=`：响应是对象 `{rows, parsed, unparsable, legacySkipped, invalidSource, folded, truncated, partial}`；
  不可读记录且超过 `legacyFormatBefore` 时返回 **502**，body 同样是该对象并带 `code=STOCK_EVENT_FORMAT_UNSUPPORTED`、`error`、`device`、`sample`；
  **没有方向过滤**。
- 两个接口的查询窗口都被截到「设备归属当前客户之后」（`customer_assigned_at` + 30 秒），上界是服务端授权时刻；平台管理员同样受限。
- 出入库行：`eventId, tank, direction, oilType?, source, beginTime, endTime, begin/endLevelMm, begin/endVolumeL, begin/endTempC, begin/endWeightKg, netVolumeL, duringDeliveryVolumeL?, deliveredVolumeL, time`。
- 小程序的 `miniprogram/utils/labels.ts` 有报警类型/级别/状态与出入库方向的文案映射，未知值回退为原值。

前端没有图表库；umi 请求库的错误对象带 `response.status` 与 `response.data`，`skipErrorHandler` 会跳过全局鉴权失败处理（本仓库约定见 `src/services/ant-design-pro/apiError.ts`）。

## Goals / Non-Goals

**Goals:**
- 三个只读页和设备详情页签，能力与小程序设备详情的前三个页签一致。
- 云端给出的降级、截断、不完整信号都在界面上如实呈现，不被「空表」吞掉。
- 记录页与设备详情共用同一套组件，不写两遍。

**Non-Goals:**
- 历史趋势图（要新增图表依赖，图表形式待产品定）。
- 远程控制、指令列表（涉及物理动作与审计，单独一轮）。
- WebSocket 报警推送（先轮询）。
- 交班记录、库存记录（云端没有对应接口）。
- 服务端分页、按设备以外的维度筛选（云端不支持）。

## Decisions

**D1 共享组件放在 `src/pages/console/components/`。** `AlarmRecords({device})`、`StockRecords({device, direction?})`、`TankCard`（从总览抽出）、`DevicePicker`。
记录页 = `DevicePicker` + 记录组件；设备详情页签 = 记录组件，设备来自路由。备选：每页各写一份，否决，因为降级提示逻辑必须只有一份。
`direction` 缺省（设备详情页签）显示进出两个方向。

**D2 取数函数返回「结果」而不是在内部吞降级。**
- `listAlarms(params)` 用 `getResponse: true` 读响应头，返回 `{rows, partial}`。
- `listStockEvents(params)` 在 200 与「502 且 body 带 `rows`/`code`」两种情况下都返回同一个结果对象 `{rows, parsed, unparsable, legacySkipped, ..., degraded: {code, error, device, sample}?}`；
  其余状态抛 `ApiError`。502 的 body 不是错误而是降级结果，这样页面不会把它当成通用失败。
- 两者都设 `skipErrorHandler`，失败经 `toApiError` 转交鉴权处理，页面按 `authHandled` 静默。
- `device` 进路径或查询串前由 `URLSearchParams`/`encodeURIComponent` 转义。

**D3 通用的「窗口 + 轮询」状态用一个 hook：`useRecordsQuery`。** 负责：当前窗口（默认滚动的最近 24 小时，用户选过范围则固定）、请求序号（旧响应丢弃）、
30 秒轮询、手动刷新、加载/失败/重试状态；卸载时丢弃在途响应。默认窗口每次请求按当前时间重新计算；用户选了范围则不再滚动。
与总览页已有的 `seq` 写法一致，但总览不改（避免扩大范围）。

**D4 降级提示由纯函数生成：`summarizeStock(result) → Notice[]`，`summarizeAlarms({rows, partial}) → Notice[]`。**
`Notice = {level: 'error'|'warning'|'info', id, values}`，组件只负责渲染。这样每一种降级都能用不带 DOM 的单测覆盖，并且文案走 i18n。
空状态只在**没有任何降级提示且 rows 为空**时才出现。

**D5 前端分页与方向过滤。** 一次 `limit=500`、`sort=false`；方向过滤在前端对这 500 条做，因此入库页显示的条数可能少于 500，截断提示依然按云端 `truncated` 判定
（过滤前）。antd `Table` 自带分页。备选：服务端分页，否决——云端没有总数，也没有方向过滤。

**D6 设备选择与 URL 同步。** `DevicePicker` 读 `GET /api/devices`；选中设备写回 `?device=`（`history.replace`）；URL 里的设备不在列表则不请求并提示。
设备列表失败给错误与重试，空列表给「还没有绑定设备」。

**D7 设备详情页签与 URL 同步。** `?tab=snapshot|alarms|stock`，非法回退 `snapshot`，切换用 `history.replace`。路由参数 `:deviceName` 用 `decodeURIComponent`。
非活动页签不挂载，避免三个页签同时轮询。

**D8 文案。** 报警类型/级别/状态、出入库方向与来源的映射放 `src/utils/labels.ts`，与小程序 `utils/labels.ts` 保持同样的键；`label(map, key)` 对未知值返回原值，空值返回 `--`。
界面文案走 `src/locales/{zh-CN,en-US}/pages.ts`（`pages.console.*`），菜单名沿用现有。

**D9 实时数据页签复用总览的取数函数。** `getLatestTelemetry` 已把 404 当作 `null`（从未上报）。403 非账号失效时它会原样抛出，详情页单独识别并显示「无权查看」，不混同「从未上报」。

## Risks / Trade-offs

- [云端窗口下界导致看不到转移前的记录，用户误以为数据丢了] → 记录页固定提示该规则。
- [500 条截断：高频报警的设备在 24 小时内可能超过 500 条] → 满 500 条即提示缩小范围；不做服务端分页是云端限制。
- [方向过滤在前端，入库页在被大量出库记录占满的窗口里可能只剩很少行] → 截断提示按过滤前的 `truncated` 判断，用户可缩小窗口；若实际使用中常见，再请云端加方向过滤。
- [设备时钟快于服务器时，最新几条记录会被上界隐藏] → 云端注释已说明，属于已知代价；本变更不处理，仅在设计中记录。
- [`X-Records-Partial` 只能用 `getResponse` 读取，服务函数签名与总览用的不同] → 取数函数统一返回结果对象，调用方不接触响应头。
- [联调拿不到历史数据：清空本地库后设备归属时间被重置，旧记录被窗口截掉] → 只能验证空窗口与接口形状；降级与截断分支靠单测与接口 mock 覆盖，并在 PR 里如实说明。
- [客户账号要有已绑定设备才有数据，而扫码绑定未完成] → 联调用平台管理员账号；客户账号路径只验证「没有设备」的空状态。

## Open Questions

- 入库/出库页是否需要服务端方向过滤？先观察前端过滤的实际体验，需要时由云端另提。
- 默认 24 小时窗口对低频设备是否太短？先沿用云端默认，是否放宽由产品决定。
