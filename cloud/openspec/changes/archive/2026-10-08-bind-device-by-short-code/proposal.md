## Why

设备屏幕/标签上的二维码只编码 6 位短码（`000001`），而设备在云端登记的名字是出厂编号 `ZCT000001`，二维码里也没有配对密钥。现有 `POST /api/devices/bind` 要求 `{deviceName, pairingToken}`，扫码拿不到这两个值，小程序/PC 的扫码绑定走不通。短码公开且可枚举，不能当凭证；但设备已由平台管理员登记并分配给客户，`BindUserDeviceIfAllowed` 本来就把绑定限制在“设备归属与用户所属客户一致”，客户归属可以承担授权依据。

## What Changes

- `POST /api/devices/bind` 的 `pairingToken` 改为可选。带 `pairingToken` 时行为与现在完全一致（含时序等价与统一 401）。
- 不带 `pairingToken` 时进入**短码绑定**：请求体的 `deviceName` 作为扫码原文，云端**只在调用者所属客户的设备里**把它解析成真实设备名：精确匹配 `device_name = 原文`，否则匹配 `device_name = 前缀 || 原文`（前缀取自服务端配置，初始只有 `ZCT`）。
- 短码绑定允许调用者所属客户的 `customer_admin` 与 `customer_user`（与带密钥路径一致：同客户任何角色都可绑定本客户设备）。`platform_admin` 跨客户、短码会有歧义，一律按失败处理，需要时用带密钥路径。
- 短码绑定成功时响应带解析后的真实 `deviceName`；失败（无此设备、他客户设备、未分配、短码有歧义、角色不允许、用户已停用）全部返回与“密钥错误”相同的 401。
- 校验与插入仍是同一条语句，并对用户行、设备行加共享锁（沿用现有的转移竞态保证）。

不做：不改配对密钥的铸造与轮换，不改二维码内容格式，不做“申请—批准”流程（同客户成员直接绑定），不改小程序/管理后台。

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `tenant-access-control`：「配对绑定的租户校验」——绑定不再必须带配对密钥；新增无密钥的短码绑定路径及其角色、范围、错误一致性要求。

## Impact

- 代码：`internal/api/devices.go`（`bindDeviceRequest`、`bindDeviceHandler`）、`db/queries/user_devices.sql`（新增按候选名解析并绑定的查询，需重新生成 sqlc）、配置里新增短码前缀列表。
- API：`/api/devices/bind` 向后兼容（原请求形状不变）；新增响应字段语义不变，`deviceName` 在短码路径下为解析后的完整名。
- 安全：授权依据从“持有密钥”变为“同客户成员 + 设备已被管理员分配给该客户”。跨客户猜短码一律 401；同客户内猜短码只能绑到本客户已有的设备（与带密钥路径下同客户成员可绑的范围相同）。
- 文档：`CLAUDE.md`、`docs/` 中配对流程的说明；`yeweiyi-miniapp` 与 `yeweiyi-admin` 的 CLAUDE.md 跟进（本变更落地后）。
- 假设（待确认）：出厂编号形如“型号前缀 + 6 位”，当前只有 `ZCT`；同一客户内不会同时存在 `000001` 与 `ZCT000001` 两个设备（若存在，精确匹配优先）。
