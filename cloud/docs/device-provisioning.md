# 设备配网：ThingsBoard Provisioning（issue #46，对应设备端 #44）

现场只手输一次贴纸上的设备编号；设备用编号 + provision key/secret 向 ThingsBoard 换取 access token。
本文是云端侧的配置、实测结论与运维手册。

## 1. 实测结论（ThingsBoard CE 4.2.1.1，2026-10-07）

用 Paho 以用户名 `provision`、**空密码**连 `tcp://…:1884`，订阅 `/provision/response`，向
`/provision/request` 发布 `{"deviceName","provisionDeviceKey","provisionDeviceSecret"}`：

| 场景 | 响应 |
|---|---|
| 名单内、未注册 | `{"status":"SUCCESS","credentialsType":"ACCESS_TOKEN","credentialsValue":"<token>"}` |
| 同一编号再次注册 | `{"status":"FAILURE","errorMsg":"Failed to provision device!"}` |
| 编号不在名单 | `{"status":"FAILURE","errorMsg":"Failed to provision device!"}`（与上一行**完全相同**） |
| key/secret 错误 | `{"status":"NOT_FOUND","errorMsg":"Provision data was not found!"}`（**第三种状态**） |

对设备端的影响：

1. 请求/响应字段与官方 API 一致，issue §3 的契约成立。
2. **「编号未登记」与「编号已注册」无法从报文区分**，设备端不能给出两种不同提示，统一提示
   「编号无效或已注册，请联系运维」。
3. 设备端必须处理 `NOT_FOUND`（key/secret 打错或平台侧被重置），不能只认 `SUCCESS`/`FAILURE`。
4. 注册成功后设备被打上服务端属性 `provisionState = provisioned`；删除该属性即可重新注册。
5. **重新注册返回的是同一个 token，不会轮换**。所以「删属性后重配」不会让旧 token 失效，
   丢失 token 的设备可以安全地重新领取。
6. 用户名 `provision` + 空密码在 4.2.1.1 的原生 MQTT 传输上可连通（Paho Go 客户端）。
   2013 年版 Paho（`mqtt-client-0.4.0.jar`）仍需真机验证，**未验证**。

## 2. 一次性配置

`tbconfig apply` 会把 `deploy/tb-config/device-profile-yeweiyi-tank-terminal.json` 写入 ThingsBoard，
策略为 `CHECK_PRE_PROVISIONED_DEVICES`。**不要**改成 `ALLOW_CREATE_NEW_DEVICES`：拿到 key/secret 的人
就能往平台注册任意设备。

- 首次启用时 `tbconfig` 随机生成 `provisionDeviceKey` / `provisionDeviceSecret`，**只打印一次**，
  交给 Android 构建写入 `local.properties` 的 `PROVISION_DEVICE_KEY` / `PROVISION_DEVICE_SECRET`，
  不进任何仓库。
- 之后重复 `apply` 会保留已有的一对。ThingsBoard 对更新里缺省的 key/secret 是**清空**（已实测），
  所以 `tbconfig` 显式带上；不要绕过它用控制台或脚本直接覆盖模板。
- 轮换：在 ThingsBoard 控制台修改模板的 key/secret，再重新构建 APK。已注册设备不受影响。

## 3. 预注册设备导入

设备名 = 贴纸编号，类型 = `yeweiyi-tank-terminal`。模板见 `deploy/provisioning/devices-template.csv`
（列：`name,type,label`）。在 ThingsBoard 控制台「设备 → 导入」选择该 CSV，列映射为
名称 / 设备类型 / 标签。

出货清单格式、由谁何时交给云端：**待生产 / 云端确认**，未定。

注意云端有两处设备登记：ThingsBoard 与 Go 业务库，仅靠名字一致相连（见 `internal/tbclient/devices.go`）。
CSV 只建立 ThingsBoard 一侧；设备归属客户、用户绑定仍走业务 API。

## 4. 运维手册

| 场景 | 设备端表现 | 云端处理 |
|---|---|---|
| 编号不在名单 | `FAILURE`（与「已注册」同文） | 核对出货清单，补导入该编号 |
| 同一编号已注册（恢复出厂、清数据、重刷） | `FAILURE` | 删除该设备服务端属性 `provisionState` 后重新配网；token 不变。或后台查出 token 让现场手填 |
| 编号误输成另一台未注册的编号 | 领走了那台的身份，那台以后注册失败 | 见下方「误领与换机」：先给被误领的那台**轮换 token**（已实测可使旧 token 立即失效），再清 `provisionState` 让正确的屏重新领 |
| key/secret 不对 | `NOT_FOUND` | 核对 APK 内置值与模板当前值；必要时轮换并重新打包 |
| 网络 / broker 不通 | 超时 | 无需处理，现场重试 |

删除 `provisionState`：控制台设备详情 → 属性 → 服务端属性，或
`DELETE /api/plugins/telemetry/DEVICE/{id}/SERVER_SCOPE?keys=provisionState`。

### 误领、重装与换机（#62，2026-10-17 实测）

**只删 `provisionState` 不是撤销**：它只让这个编号能再次被领取，领到的还是**同一个 token**，旧 token 照常可连。
实测（`internal/tbclient/provisioning_e2e_test.go`，真实 ThingsBoard）：

| 场景 | 处理 | 结果 |
|---|---|---|
| 同一块屏清数据/重装，token 丢了 | 删 `provisionState` → 重新输入编号 | 领回同一个 token，安全，无需轮换 |
| 屏幕 A 误输了 B 的编号（B 尚未激活）领走 B 的身份 | 对 B：`SetAccessToken` 轮换 token（控制台「设备 → 管理凭据」同效）→ 删 B 的 `provisionState` → 正确的屏输入 B 的编号 | 旧 token（A 手里那份）**立即不能连**；重新领取得到新 token |
| 身份泄露、屏被盗 | 同上，对该设备轮换 token | 旧 token 失效；业务记录以 `device_name` 为键，不丢 |
| 换新设备（#61 一对一） | **用新编号**登记新设备，油站走 `replace-device`；旧设备保留历史，旧设备如不再用建议轮换其 token | 不迁移历史、不继承旧设备的授权 |

重新领取只认「名单内且未打 `provisionState`」：第二块屏输入已激活的编号得到 `FAILURE` 且不带 token，**不能静默接管**；
但**输入有效不等于实物匹配**——名单策略无法识别「贴错/输错到另一个有效未激活编号」，只能靠安装人员对照外壳编号与
网页管理平台上的客户/油站信息人工确认（大屏不查客户信息，匿名编号查询也不会泄露客户）。

## 5. 安全说明（已接受的风险）

key/secret 打包进 APK，可被逆向。预注册策略下持有者只能领取名单内且未注册的编号，不能凭空造设备，
已注册设备不受影响。这推翻了此前模板里「不使用自注册，因为一机一密」的决定；配对密钥
（`pairing-secret`）仍与 ThingsBoard/MQTT 凭据隔离，不受影响（CLAUDE.md 约束 7）。

### #62 云端核查结论（2026-10-17，无真机，仅云端 + 本地 ThingsBoard）

| 项 | 状态 | 证据 |
|---|---|---|
| 预登记名单策略 | 已实现、已验证（本地 ThingsBoard） | `deploy/tb-config/device-profile-yeweiyi-tank-terminal.json`、`provisioning_e2e_test.go`：未登记号 `FAILURE`、错 key/secret `NOT_FOUND` |
| 业务库登记幂等、不碰凭据、不跨客户迁移 | 已实现、有测试 | `POST /api/devices`；`register_device_test.go`（幂等、并发、他客户设备 409 且不迁移、认领未分配） |
| 首次领取、已激活再领、不被静默接管 | 已验证（本地 ThingsBoard） | `provisioning_e2e_test.go` |
| 误领后撤销 | 已验证（本次补上，此前「未实测」） | 轮换 token 后旧 token 立即不能连 |
| TB 名单与业务库两侧设备名一致 | **部分实现**：仅靠名字相连，无校验 | 只在 TB 不在业务库：不会被采集；只在业务库：`ingest` 每 5 秒重试并记日志。**缺口**：没有一次性对照两侧的工具。最小补齐：只读 CLI（如 `cmd/devicecheck`）列出两侧差集，不新增 API |
| 短码与完整设备名映射 | 已实现、有测试 | `POST /api/devices/resolve`（精确优先再 `pairing.codePrefixes`，只在调用者客户内）；`bind_shortcode_test.go` |
| 匿名编号查询不泄露客户信息 | 已实现 | resolve 需登录，且未授权/不存在同一个 404 |
| 换机按新编号、不继承历史/授权 | 已实现、有测试 | #61 `station_replace_test.go` |
| 不增加 #60 开通门槛 | 已满足 | 配网与登记不经过客户开通状态 |
| 2013 年版 Paho 用户名 `provision`+空密码 | **未验证**（需真机） | — |
| 8883/TLS 上的配网 | **未验证** | 本地只测了 1884 明文口 |
| 本地凭据保存失败后的恢复、超时重试、取消确认、重启恢复 | **未验证**（设备端行为） | 云端侧：重新领取返回同一 token，故重试安全 |
| APK 内置 key/secret 可被逆向 | 已接受的风险 | 预注册策略下只能领名单内未激活的编号；已激活的不受影响；人工确认**不能**消除抢先领取 |

## 6. 未完成

- 2013 年版 Paho 的真机/本地 broker 实测。
- 生产 8883/TLS 端口上的 Provisioning 未测（本次只测了开发环境 1884 明文口）。
- 出货清单约定。
