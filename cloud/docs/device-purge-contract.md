# 设备彻底删除：云端与后台契约（v1.0）

状态：**云端实现中（分支 `feat/device-purge-83`），admin 未对接。** 主需求 [cloud#83](https://github.com/hisummer2406/yeweiyi-cloud/issues/83)，
后台 [admin#29](https://github.com/hisummer2406/yeweiyi-admin/issues/29)。设计与规格：`openspec/changes/device-purge/`。
标注同 [罐授权契约](tank-grants-contract.md)：**【需求】【选择】【待确认】【现状】**。

这是"业务记录永久保存"（`CLAUDE.md` 约束 5）的**唯一例外**。换机、客户转移仍不删除任何历史。

> **2026-11-24 起网页端发起已关闭（#69）。** `POST /api/devices/:device/purge` 对平台管理员恒返回
> `410 {"code":"device_purge_disabled"}`（不读请求体、不建也不重启任务，只写 `denied` 审计）；其他角色仍是原来的
> `403`。日常移除设备改为**解除油站关联**（`PUT /api/devices/:device/station`，`{"stationId": null}`，见
> [油站与换机契约 §3.1](station-device-replacement-contract.md)），保留设备档案、业务历史、ThingsBoard 实体、凭据与遥测。
> `GET /api/device-purges[/:id]` 保留供审计；进程重启时仍续跑 `running` 任务；`failed` 任务（仍是写入栅栏）与确需的永久清除
> 由授权运维单独处理，目前没有命令行工具。下文 §2.1、§7 描述的发起与后台对接流程已停用，留作历史。

## 1. 谁能删

- 【需求】只有数据库中当前角色为 `platform_admin` 且未停用的账号。其他角色在解析请求体之前得到
  `403 {"error":"not authorized"}`，并写 `denied` 审计（`action=device.purge`）。
- 【需求】一次一台，按完整设备编号；没有批量、没有撤销。

## 2. 接口

### 2.1 发起 `POST /api/devices/:device/purge`

```json
{ "confirmDeviceName": "ZCT000123", "reason": "误登记，客户确认作废" }
```

| 结果 | 状态码 | 体 |
| --- | --- | --- |
| 新建任务 | `202` | `{"purge": Purge}` |
| 该设备已有未完成（`running`/`failed`）任务 | `200` | `{"purge": Purge}`——同一任务，并重新开始执行剩余步骤 |
| `confirmDeviceName` 与路径不完全相同（大小写、前导零都算） | `400` | `code=confirm_mismatch` |
| `reason` 为空或超过 500 字符 | `400` | `code=reason_required` |
| 设备不存在（含已删完） | `404` | `code=device_not_found` |
| 没有配置 ThingsBoard | `503` | `code=thingsboard_unavailable`，不建任务 |

- 【选择】用 POST 而不是 `DELETE /api/devices/:device`：要带确认体，结果是异步任务。
- 【需求】200/202 都**不代表删完**，只代表任务存在；以 `status` 为准。

### 2.2 查询

- `GET /api/device-purges/:id` → `{"purge": Purge}`；不存在 `404 code=purge_not_found`。
- `GET /api/device-purges?deviceName=ZCT000123` → `{"rows": [Purge...]}`，新的在前；不带 `deviceName` 返回最近 100 条。
- 只对平台管理员开放，其余同 §1。

### 2.3 `Purge`

```json
{
  "id": 7,
  "deviceName": "ZCT000123",
  "status": "running | failed | completed",
  "step": "thingsboard | business | verify | done",
  "error": "仅 failed 时有：失败原因（可能含 ThingsBoard 原始错误）",
  "counts": { "tbKeys": 42, "stockEvents": 310, "alarmEvents": 88, "recordRejects": 0,
              "recordBackfills": 1, "telemetryHourly": 5120, "auditLog": 17,
              "controlCommands": 9, "tankGrants": 3, "userDevices": 0, "pairingSecrets": 1 },
  "requestedBy": { "id": 1, "username": "admin" },
  "reason": "误登记，客户确认作废",
  "customerId": 5,
  "stationId": 12,
  "startedAt": "2026-10-27T08:00:00Z",
  "finishedAt": "2026-10-27T08:00:41Z"
}
```

- `step` 是**下一个要做/正在做**的步骤；`failed` 时就是失败的那一步。`completed` 时为 `done`。
- `counts` 只有数字，随步骤推进逐项出现；没有任何被删记录的内容。
- `customerId` / `stationId` 是发起时设备所属的客户与油站（可空），供后台显示"删除前位于…"。

## 3. 执行过程

| 顺序 | 步骤 | 内容 | 幂等方式 |
| --- | --- | --- | --- |
| 0 | 建任务（同步） | 锁设备行，写任务；从此该设备**被栅栏挡住**（§4） | 同设备至多一个未完成任务（唯一索引） |
| 1 | `thingsboard` | 按名查设备；列出全部遥测键，按键删除全部数据并复查为空；删除设备实体（凭据、属性随之删除）；按名复查 404 | 查不到即视为已完成 |
| 2 | `business` | 一个事务：出入库、报警、拒收原文、回补标记、小时汇总、该设备的审计（本删除动作的审计除外）、设备行（级联：命令、罐授权、旧绑定、配对密钥） | 删除不存在的行无副作用 |
| 3 | `verify` | 逐表按设备名计数为 0，ThingsBoard 按名 404 → `completed` | 只读 |

- 【需求】失败停在 `failed`，记 `step` 与 `error`；不自动无限重试。再次 `POST` 发起即从 `step` 继续。
- 【需求】服务启动时自动续跑 `running` 任务（被重启打断的）；`failed` 等人工再次发起。
- 【现状】生产是单实例；同一任务在本进程内不会并发执行。多实例时 SQL 栅栏仍成立，但执行器没有跨实例互斥。

## 4. 栅栏：删除中写不回

从任务建立到完成：

- 实时订阅与定时回读停止；设备从订阅、小时汇总、回补工具共用的设备清单中消失。
- 出入库、报警、拒收原文、回补标记、小时汇总的写入语句自带检查，写不进去（迟到的消息、在途的回补都一样）。
- 登记（`POST /api/devices`）、配对密钥签发、油站分配、换机、罐授权、发命令 → `409 code=device_purge_in_progress`。
- 读接口不变：删除完成前设备和记录仍可读（逐步消失），完成后设备从所有列表消失、按名访问与"不存在"相同。

## 5. 清理范围与边界

【需求】完成后，云端在线数据中按该设备编号查不到：ThingsBoard 遥测与设备实体（含凭据、属性）；业务库
`stock_events`、`alarm_events`、`record_rejects`、`record_backfills`、`telemetry_hourly`、`audit_log`、`control_commands`、
`user_tank_grants`、`user_devices`、`device_pairing_secrets`、`devices`。覆盖历任客户期间的全部数据。

保留：客户、油站（变为空油站）、账号、其他设备及其历史；本次删除的任务记录与 `device.purge` 审计（不含业务载荷）。

【现状·不在范围】设备本机存储；导出的文件；PostgreSQL 与 ThingsBoard 的离线备份（按备份轮转周期过期，见
`deploy/README.md`）。云端不宣称清除了这些副本。

## 6. 删除之后：同编号重新接入

- 【选择·用户 2026-10-27 确认】ThingsBoard 设备实体被删除，设备旧凭据失效、立即断联。要让这台机器以同一编号重新接入：
  运维在 ThingsBoard 重新预登记该编号（出货 CSV 流程），设备重新领取凭据，平台管理员再 `POST /api/devices` 登记。
  【待确认·设备端】旧凭据失效后设备能否自行重新领取。
- 【需求】重新登记的设备是全新设备：没有授权、配对密钥、油站，没有任何旧历史。
- 【选择·用户 2026-10-27 确认】对有已完成删除任务的编号，信封 `ts` **不晚于**最近一次删除完成时刻的出入库、报警、
  拒收原文，以及开始于该时刻之前的小时汇总，一律不写入——挡住设备把本机旧记录重放上来。已知限制：设备时钟
  落后时，新记录可能被当作旧记录丢弃；重新接入前应确认设备时间正确。

## 7. 后台（admin#29）对接要点

- 入口仅平台管理员可见；弹窗写明"不可恢复"，列出设备编号、所属客户与油站，要求输入完整编号与原因。
- 发起后轮询 `GET /api/device-purges/:id` 直到 `completed` 或 `failed`；`failed` 显示 `step`/`error` 并提供"重试"（再次 POST）。
- 页面刷新或超时后，用 `GET /api/device-purges?deviceName=` 找回任务；不要因为设备从列表消失就判断删除成功。
- 完成页提示：同编号重新接入需重新预登记（§6）。
