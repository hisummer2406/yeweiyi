# 报警、出入库记录按罐 / 方向筛选：三端契约（v1.0，待相关负责人确认）

状态：**云端已实现并通过真实数据库/ThingsBoard 测试；admin、小程序已按本契约改写，均未在真机/浏览器联调。**
主需求：[cloud#84](https://github.com/hisummer2406/yeweiyi-cloud/issues/84)；方向筛选是
[cloud#85](https://github.com/hisummer2406/yeweiyi-cloud/issues/85) 第 7 点的一部分（#85 其余部分——库存、交班记录——未做，
等设备端 [zhongchengyeweiyi#49](https://github.com/hisummer2406/zhongchengyeweiyi/issues/49) 确认记录事实）。
配套：[miniapp#15](https://github.com/hisummer2406/yeweiyi-miniapp/issues/15)、
[#16](https://github.com/hisummer2406/yeweiyi-miniapp/issues/16)、[#17](https://github.com/hisummer2406/yeweiyi-miniapp/issues/17)。
标注同 [罐授权契约](tank-grants-contract.md)：**【需求】【选择】【待确认】【现状】**。

## 1. 参数

两个接口都是**可选、向后兼容**的新增查询参数；不带时行为与此前完全相同。

| 参数 | 接口 | 合法值 | 非法时 |
| --- | --- | --- | --- |
| `tank` | `GET /api/alarms`、`GET /api/oil-in-out` | 整数 `1..64`（`protocol.MaxTankCount`），协议罐号，不是数组下标 | `400 invalid_tank` |
| `direction` | `GET /api/oil-in-out` | `IN` / `OUT`（大小写敏感，与记录里的 `direction` 相同） | `400 invalid_direction` |

- 【选择】`tank=0`（设备级记录，payload 无罐号）不能作为筛选值：设备级记录只对管理员可见，不带 `tank` 的整机查询照常包含它们。
- 【需求】参数校验在授权之前：非法值对任何调用者都是同一个 400，不泄露设备是否存在。

## 2. 语义

- 【需求】**先筛选、后分页**：设备、客户归属时间窗、按罐授权和 `tank`/`direction` 都在 SQL 的 `LIMIT/OFFSET` 之前生效，
  `limit`、`offset`、`/api/oil-in-out` 的 `truncated` 全部针对筛选后的结果。另一罐或另一方向的记录占满整机第一页时，
  目标记录照样出现在筛选结果的第一页；只有筛选结果确实为空才返回空。
- 【需求】授权：设备级检查不变（跨客户、不存在、未授权仍是同一个 403）。设备检查通过后，**指定了调用者不能查看的罐**
  返回 `403 code=tank_not_authorized`——该罐存在与否答案相同，不能借此数出设备有几个罐；不会以空列表掩盖，也不是会话失效。
  管理员可指定任意罐号（没有记录即空）。
- 【现状】响应形状不变：`/api/alarms` 仍是裸数组（未完成回补时带 `X-Records-Partial: true` 头），`/api/oil-in-out` 仍是
  `{rows, parsed, unparsable, legacySkipped, invalidSource, folded, truncated, partial, …}`，200 与 502 同一信封。

## 3. 完整性提示的适用范围

| 字段 | 带 `tank` 时 | 带 `direction` 时 |
| --- | --- | --- |
| `invalidSource` | 只计该罐（且仍只计调用者可见的罐） | **整机**（不按方向区分，宁多报不漏报） |
| `unparsable` / `legacySkipped` / `sample` | **整机**：原文无法解析，归属不到罐或方向 | 同左 |
| `partial`（`X-Records-Partial`） | 整机：设备初次回补是否完成 | 同左 |

- 【需求】客户端在筛选视图里显示 `unparsable` 时须说明它是「本设备」的记录无法解析、可能影响当前列表，不能说成「该罐有 N 条错误」。
  员工照旧拿不到 `sample` 原文（#63）。

## 4. 客户端

- 小程序（miniapp#15/#16/#17）：设备详情报警页签带当前罐的 `tank`；「我的记录」入库/出库页带 `direction`，罐筛选带 `tank`。
  旧云端（无本契约）会忽略未知参数并返回整机数据，客户端仍保留按行过滤作为兜底，不会把别的罐显示进来。
- admin `/console`：报警、入库、出库记录加罐号筛选，入库/出库改由服务端按方向筛选。

## 5. 待确认

- 【待确认】参数名 `tank` / `direction` 与错误码 `invalid_tank`（沿用罐授权接口已有的码）/ `invalid_direction` 由小程序、admin 负责人确认。
- 【待确认】库存、交班记录的筛选与本契约同形（cloud#85，草稿见 [shift-stock-records-contract](shift-stock-records-contract.md)），定稿后在此文档追加，不另起参数名。
