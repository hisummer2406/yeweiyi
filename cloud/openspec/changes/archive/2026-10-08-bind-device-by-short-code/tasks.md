## 1. 查询与配置

- [x] 1.1 在 `db/queries/user_devices.sql` 新增 `BindUserDeviceByCodeIfAllowed`：入参 `user_id`、候选名数组（精确匹配优先的顺序）；`u` 限 `role IN ('customer_admin','customer_user')`、`customer_id` 非空且未停用；`d` 限 `customer_id = u.customer_id`，按候选顺序取一行并 `FOR SHARE`；插入沿用幂等逻辑；返回解析后的 `device_name`（无行表示拒绝）。运行 sqlc 生成。
- [x] 1.2 配置新增短码前缀列表（默认 `ZCT`，空则只精确匹配），并在 `.env.example`/文档中说明。

## 2. Handler

- [x] 2.1 `bindDeviceRequest.PairingToken` 去掉 `binding:"required"`；有值走原路径，逻辑不变。
- [x] 2.2 无值走短码路径：校验原文 `^[0-9A-Za-z]{1,32}$`，生成候选，调用 1.1 的查询；不调用 `EnsureDevice`；失败统一返回现有 401 文案；成功返回解析后的 `deviceName`。
- [x] 2.3 更新 `bindDeviceHandler` 的文档注释，说明两种凭证与不做的事（不单次、不改二维码格式）。

## 3. 测试

- [x] 3.1 短码路径：`customer_admin` 与 `customer_user` 都能绑定本客户设备且返回完整名；他客户、未分配、不存在、`platform_admin`、停用用户、无客户的用户、非法原文全部得到与密钥错误相同的 401，且不产生 `user_devices`/`devices` 新行。
- [x] 3.2 精确匹配优先；前缀列表为空时只精确匹配。
- [x] 3.3 转移竞态：沿用现有绑定/转移竞态测试的方式，覆盖短码路径。
- [x] 3.4 回归：现有带密钥绑定、时序等价（`pairing_timing_test.go`）测试保持通过。

## 4. 文档与后续

- [x] 4.1 (CLAUDE.md 已更新；归档时同步主规格。注：停用用户由认证中间件返回 403，先于本路径的 401) 更新 `CLAUDE.md` 与 `docs/` 中配对流程说明；归档时同步 `tenant-access-control` 主规格。
- [x] 4.2 通知 `yeweiyi-miniapp`（扫码后直接发原文，省略 `pairingToken`）与 `yeweiyi-admin`（CLAUDE.md 里“小程序扫码绑定”段与配对密钥页说明）跟进。
