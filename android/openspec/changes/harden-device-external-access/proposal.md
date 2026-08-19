## Why

小程序/PC 对接把这台设备的受众从"设备旁边、能凭经验判断异常的现场操作员"扩大到"局域网内任意主机 + 经云端的终端用户"，同时 `device-write-back-sync` 还要给设备加上写能力（改配置、改历史记录）。三处现有缺口在这个新受众/新能力面前，风险等级发生了实质变化，必须在写接口真正上线前处理，而不是继续停留在"已发现未修"：

1. **LAN HTTP 接口零鉴权**——现状只读时代价是"局域网内谁都能看数据"；`device-write-back-sync` 一旦上线写接口，代价升级为"局域网内谁都能改配置、篡改出入库/交班记录"。
2. **MQTT 鉴权硬编码 `admin`/`password`**（`BackService.java`，与 `ApiConfig` 无关）——现状只读时代价是"知道这组账号密码能看到上报数据"；写回同步做完后，MQTT 还要承载配置下发和记录修正指令，代价升级为"知道这组账号密码能对设备下发伪造的配置变更"。
3. **`BackService.sendAlarms()`/`sendUnloadOils()` 失败仍返回 `true`**（`docs/重构方案.md` 4.5.1 记录的已知缺陷，此前只加了日志未改行为）——这条路径正是小程序"报警实时推送"依赖的同一条 MQTT 发布路径（`thing/event/msg/post`，见 `docs/小程序与PC端集成设计.md` §4.2）。现状网络故障时报警静默丢失、无人知晓，以前只影响设备本地这一份记录，现在直接决定终端用户能不能收到报警推送。

## What Changes

- **LAN 接口鉴权**：`/login` 校验成功后签发一个短期 token；`/alarm`、`/oilDischarge`、`/baseOilcan`、`/upload` 及 `device-write-back-sync` 即将新增的写接口统一校验该 token。不引入 OAuth/JWT 等重型方案（呼应硬约束1，本设备是嵌入式终端不是 web 后端），用最小可行的 token 校验即可。
- **MQTT 凭据外置**：把 `BackService` 里硬编码的 `userName="admin"`/`passWord="password"` 迁到 `local.properties` + `BuildConfig`，复用阶段0已经建好的密钥外置模式（`ApiConfig` 的 `PRODUCT_KEY`/`PRODUCT_SECRET`/`REQUEST_ENCRYPT_KEY` 就是这么做的）。**这组硬编码凭据本身也要视为已泄露**（在 git 历史里），需要在生产 broker 上完成一次真实轮换，不是只改代码。
- **报警上报失败行为修正**：`sendAlarms`/`sendUnloadOils` 在发布失败时不再返回 `true`；断连时不再静默丢弃（`publish()` 里 `if (client.isConnected())` 分支目前直接丢弃且已加日志暴露，本变更要在此基础上决定入队重发还是显式失败上抛，见 Impact）。

## Capabilities

### New Capabilities
- `security/lan-api-authentication`：LAN HTTP 接口的 token 鉴权
- `security/mqtt-credential-management`：MQTT 凭据外置与轮换
- `reliability/alarm-delivery-honesty`：报警/卸油上报的返回值与实际投递结果一致，断连时不静默丢弃

### Modified Capabilities
（无——这三块此前均未被记录为 OpenSpec 能力）

## Impact

- **受影响代码**：`web/handler/LoginHandler.java`（签发 token）、`web/handler/` 其余 handler 及 `device-write-back-sync` 即将新增的写接口（校验 token）、`service/BackService.java`（MQTT 凭据来源、`sendAlarms`/`sendUnloadOils`/`publish()` 的失败处理）、`config/ApiConfig.java`（新增 MQTT 凭据常量）、`local.properties`/`local.properties.template`/`app/build.gradle`（新增注入项）。
- **`sendAlarms` 失败后具体该怎么办，是本变更要拍板的行为变更**：`docs/重构方案.md` 4.5.1 列了两个待决策方向——失败时返回 `false`（会改变 AIDL 调用方看到的结果，需先确认调用方如何处理失败）、断连时入队重发而非丢弃（改变现有行为，需要引入本地缓存队列）。本变更的 design.md 会给出具体选择，不能再停留在"两个方向都待定"。
- **依赖关系**：本变更是 `device-write-back-sync` 的前置项——该变更的 LAN 写接口任务组（tasks.md 第3组）明确写了"暂时沿用零鉴权，待鉴权方案定案后补"，本变更就是补上这个"定案"。建议顺序：本变更先行或至少与 `device-write-back-sync` 第1、2组并行，在其第3组（LAN 写接口）开始前完成。
- **对三条硬约束的态度**：respects 约束1（token 鉴权与凭据外置都是局部改动，不涉及框架级重写）；respects 约束2（`sendAlarms` 行为变更前需要补测试锁定"断连/发布异常/发布成功"三种场景的当前与预期行为，不能裸改）；约束3 不直接适用（不是协议解码层改动），但 MQTT 收发路径的改动仍需要真实网络环境验证，本机无法完全验证broker断连重连的真实时序。
