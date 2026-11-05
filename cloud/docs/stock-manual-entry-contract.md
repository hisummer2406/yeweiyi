# 出入库手工补录契约（cloud#69）

小程序「设置 → 补录记录」：管理员**新增**一条设备漏报的出入库记录（断电、故障期间）。这是插入新记录，**不改写**
设备已上报的任何记录（"记录修正"是另一件事，仍无设计）。#13（同 `eventId` 重复上报折叠）云端早已在写入时完成，
见 `docs/protocol-v1-stock-event.md`。

## 接口

### `POST /api/devices/:device/stock-events`

```json
{ "direction": "IN", "tank": 3, "time": "2026-10-14T08:30:00+08:00",
  "beforeLevelMm": 1333.3, "volumeL": 540, "reason": "断电期间漏记", "requestId": "可选，幂等键" }
```

| 字段 | 规则 |
|---|---|
| `direction` | `IN` / `OUT` |
| `tank` | 1–16 |
| `time` | 带时区的完整 RFC 3339；**不得晚于现在**（容忍 1 分钟时钟差）；不得早于设备归属当前客户的时刻（否则补了也看不到，`time_before_visible`） |
| `beforeLevelMm` | ≥ 0，毫米 |
| `volumeL` | > 0，升，两个方向都填正数（出库的罐内变化由云端记为负） |
| `reason` | 必填，≤ 200 字，进审计 |
| `requestId` | 可选，≤ 64。同一 `(设备, requestId)` 永远对应同一条记录：重试返回 `200` 和原记录；内容不同 → `409 request_id_reused` |

成功 `201`（重放 `200`），返回与 `/api/oil-in-out` 一行同形：`source="MANUAL"`、`origin="cloud"`，`eventId` 为
`manual-…`（云端生成，与设备 id 不会冲突）。`deliveredVolumeL = volumeL`；表单不知道的量（`endLevelMm`、前后体积、
温度、重量）为 **`null`，不是 0**。

错误（`400`，带 `code`）：`direction_invalid` `tank_invalid` `level_invalid` `volume_invalid` `reason_required`
`reason_invalid` `request_id_invalid` `time_invalid` `time_in_future` `time_before_visible`。

### `DELETE /api/devices/:device/stock-events/:eventId`

撤销一条**云端补录**的记录，`204`（重复撤销也 `204`）。设备上报的记录（含设备端自己的 `BuLuFragment` 补录）
`409 stock_event_not_deletable`，不存在 `404 stock_event_not_found`。撤销是**软删除**：行保留（出入库永久保存），
读路径排除，审计日志保留完整内容。

## 权限与审计

- 平台管理员，或客户管理员（限本客户设备）。员工（`customer_user`）一律 `403 not authorized`（按角色拒绝，写 `denied` 审计）。
  设备范围规则同其他设备接口：跨客户、不存在同一个 403。
- 写入和审计在**同一事务**：没有审计行就没有改动。动作 `stock.manual_add` / `stock.manual_delete`，记录操作人、设备、
  内容、原因。

## 对读侧的影响（所有出入库记录）

`GET /api/oil-in-out` 的 `beginLevelMm` `endLevelMm` `beginVolumeL` `endVolumeL` `beginTempC` `endTempC`
`beginWeightKg` `endWeightKg` 现在**在记录里没有该键时为 `null`**（此前一律是数字，缺失时是 0）；自动检测的记录
这些键都有，仍是数字。每行新增 `origin`（`device` / `cloud`）。

## 已定与待定

- 已定（用户 2026-10-17）：谁能补录 = 平台/客户管理员；缺失量返回 null；提供撤销云端补录；时间须完整 RFC 3339 且不晚于现在。
- 我的决定：软删除而非物理删除；`time_before_visible` 拒绝；`requestId` 作幂等键；补录行不参与折叠（`eventId` 永不与设备重合）。
- **未做**：发现"设备端也补了同一次漏记"的重复——两边 `eventId` 不同，不会折叠，也没有自动检测；补录记录是否参与将来的
  库存/报表汇总尚未定义，若参与需按 `origin`/`source` 区分。
