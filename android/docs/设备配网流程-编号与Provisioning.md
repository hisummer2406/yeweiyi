# 设备配网：手输设备编号 + ThingsBoard Provisioning 自动获取 token

**状态**：已实现（`95f93a3`），JVM 单测通过；成功路径已真机验证（2026-10-07 真机实测（rk3562_t，Android 13）：编号 ZCT000001 领取成功，经 TLS 连上 `8.154.38.176:8883`，token 以 `enc:` 加密落盘；云端确认遥测每 30 秒更新、`provisionState = provisioned`）。失败路径（重复领取、未登记编号）未在真机上测。
**依据代码**：分支 `feature/thingsboard-device-protocol`，`95f93a3` + 工作区改动（默认 broker、CA 证书）。
**目标**：现场安装只手输一次设备编号，access token 由设备通过 ThingsBoard Provisioning 自动领取，不再手抄 token。

**设备编号获取方式定为手输**：编号只印在贴纸上（明文/二维码），设备读不到，也没有扫码能力。「出厂登记 MAC、云端按 MAC 反查编号」的方案不采用。

---

## 1. 当前实现

| 项 | 现状 | 代码 |
|---|---|---|
| 凭据录入 | 设置页（高级设置 → 云平台设置）填 broker 地址/端口、设备编号；访问令牌可留空（自动领取），手填为兜底。留空保存前弹窗回显编号确认；换了编号而令牌框仍是旧令牌时，旧令牌作废；编号没变、令牌框为空而已有令牌时保留已有令牌；领取成功后令牌回填到令牌框 | `fragment/ThingsBoardSetFragment.save()` |
| 自动领 token | 有编号、无 token 时，在 `tb-reconnect` 线程上用用户名 `provision` 临时连 broker（同一 TLS 设置），发 `/provision/request`，15 s 等应答；成功后存 token 并立即正常连接。领取期间配网页改了编号或手填了 token，领到的作废并记 `Log.w`（检查和写入同一把锁，`ThingsBoardCredentialStore.saveIfAwaitingToken()`） | `ThingsBoardManager.provisionAccessToken()`、`ThingsBoardMqttConnection.provision()`、`logic/thingsboard/ProvisionMessages` |
| 失败处理 | 云端拒绝（任何非 `SUCCESS` 的 status、凭据类型不是 `ACCESS_TOKEN`）或本机没配 key/secret：停止重试，重新保存或 App 重启后再试一次（停止状态只在内存里）；超时/网络/应答格式错：按退避重试；出异常前已收到应答的，按应答处理。状态显示在设置页状态行 | 同上，`ProvisionResult.Outcome` |
| key/secret 注入 | `sansen.provisionDeviceKey`/`sansen.provisionDeviceSecret` → `BuildConfig` → `ApiConfig.PROVISION_DEVICE_KEY/SECRET` | `app/build.gradle`、`local.properties.template` |
| 凭据存储 | deviceId 明文，accessToken 用 Keystore AES/GCM 加密后落盘 | `manager/ThingsBoardCredentialStore` |
| broker 与 TLS | 未保存过地址时默认 `8.154.38.176:8883`；`sansen.thingsboardUseTls=true` 时走 TLS，只信任 `assets/thingsboard_ca.pem`（私有 CA `yeweiyi-internal-ca`，有效期至 2036-10-03）。服务端证书 SAN 含该 IP。1883 端口能建 TCP 但不响应 MQTT，不可用 | `SharePreManager.getIpBean()`、`ThingsBoardCaStore` |
| 连接 | 没有编号时不发起连接，按退避重试；有编号无 token 时先走自动领取 | `ThingsBoardMqttConnection.connect()`、`ThingsBoardManager.attemptConnect()` |
| 保存后生效 | 改 broker 后在 `tb-reconnect` 线程断开重连，不等重启 | `ThingsBoardManager.applyUpdatedConnectionSettings()` |
| MQTT client ID | 用 deviceId | `ThingsBoardMqttConnection.connect()` |

## 2. 缺失

| 级别 | 问题 |
|---|---|
| 待确认 | 失败路径（重复领取、未登记编号、Device Profile 不匹配）未在真机上测，见 §6 |
| 低 | 手填令牌抄错时只表现为连不上，没有明确原因（暂不修：手填只是兜底路径） |
| 低 | 新文案只加在 `values/strings.xml`，en/es 待设计稿；页面仍只在中文界面开放，标签（「设备ID」「访问令牌」「端口」等）仍是硬编码 |

## 3. 实现方案

### 3.1 总流程

```
通电联网 → 本地有有效凭据？──是──→ 正常连接（现有流程）
              │否
              ▼
        设置页手输设备编号（一次，保存前回显确认）
              │
              ▼
        Provisioning：编号 + provision key/secret 换 token
              │SUCCESS
              ▼
        save(编号, token) → reconnectNow() → 正常连接
```

### 3.2 云端准备（一次性）

| 步骤 | 内容 |
|---|---|
| 1 | Device Profile 开启 Provisioning，策略选 **Check for pre-provisioned devices**（只允许名单内编号注册），把 key/secret 交给 Android 构建 |
| 2 | 按出货清单批量导入设备（CSV），设备名 = 贴纸编号，**导入时指定第 1 步开启了 Provisioning 的那个 Device Profile**（ThingsBoard 按 profile 匹配，导入到 default profile 会被判为未登记——推断，待实测） |

不用「Allow to create new devices」：拿到 key/secret 的人都能往平台里注册任意设备。

### 3.3 设备端

已实现，见 §1。

### 3.4 Provisioning 报文（ThingsBoard 官方 MQTT API）

```
连接：用户名 provision，密码空
订阅：/provision/response
发布：/provision/request
  {"deviceName":"<编号>","provisionDeviceKey":"...","provisionDeviceSecret":"..."}
响应：
  {"status":"SUCCESS","credentialsType":"ACCESS_TOKEN","credentialsValue":"<token>"}
  {"status":"FAILURE"|"NOT_FOUND"|…,"errorMsg":"..."}
```

设备端把任何非 `SUCCESS` 的 status 都当云端拒绝处理；未登记编号具体返回 `FAILURE` 还是 `NOT_FOUND` 待实测（§6）。

## 4. 异常与恢复

| 情况 | 表现 | 处理 |
|---|---|---|
| 编号不在预注册名单（或导入时 Device Profile 选错） | 非 `SUCCESS` 应答 | 设置页提示云平台拒绝，联系云端导入/改 profile 后重新保存 |
| 同一编号已注册过（恢复出厂、清数据、重刷后再注册） | 非 `SUCCESS` 应答：注册成功后 ThingsBoard 给该设备打上服务端属性 `provisionState = provisioned`，拒绝再次注册 | 云端删除该属性后重新保存，或后台查出 token 用设置页手填 |
| Keystore 密钥丢失、token 解密失败 | `ThingsBoardCredentialStore` 视为未配网，设备重新领取，必然被拒 | 同上，需云端介入 |
| 云端返回成功但凭据类型不是 `ACCESS_TOKEN`，或缺 `credentialsValue` | 设备按拒绝/应答格式错处理；云端很可能已标记该编号为已领取（推断） | 改正云端配置后**还要删除 `provisionState`**，再重新保存 |
| 领取期间配网页改了编号或手填了 token | 领到的 token 作废，日志 `Log.w` 记下编号；该编号在云端已被占用 | 需要该编号时，云端删除 `provisionState` |
| 编号输错成另一台已登记、未注册的编号 | 领走那台的身份，那台以后注册失败 | 保存前回显确认；发现后云端重置两台 token |
| 网络/broker 不通 | 超时 | 提示网络失败，可重试 |

## 5. 安全

| 级别 | 问题 | 结论 |
|---|---|---|
| 中 | provision key/secret 打包在 APK 里，可被逆向 | 预注册策略下只能领名单内、未注册的编号，不能凭空造设备，已注册设备不受影响 |
| 中 | `sansen.thingsboardUseTls` 关闭时，key/secret 和领到的 token 都以明文传输（领取连接和正常连接共用这个开关） | 正式出货的包必须打开 TLS |

## 6. 待确认

| 项 | 需要谁 |
|---|---|
| 未登记编号返回的 status（`FAILURE`/`NOT_FOUND`）、重复领取被拒、导入设备的 Device Profile 是否必须匹配（成功应答字段和 `provisionState` 已实测确认） | 云端 + 真机实测 |
| 出货清单（编号列表）由谁、何时交给云端导入 | 生产 / 云端 |
