## 1. MQTT 凭据外置（最独立，最先做）

- [x] 1.1 `local.properties.template` 新增 `MQTT_USERNAME`/`MQTT_PASSWORD` 占位项，照抄 `PRODUCT_KEY` 等现有条目的注释风格
- [x] 1.2 `app/build.gradle` 新增读取逻辑，缺失时打印 `[配置缺失]` 警告，编译为空字符串（照抄现有四个凭据的模式）
- [x] 1.3 `config/ApiConfig.java` 新增对应常量并加 javadoc 说明
- [x] 1.4 `service/BackService.java` 里硬编码的 `userName="admin"`/`passWord="password"` 改为读取新常量
- [x] 1.5 跑 `./gradlew :app:assembleDebug`，确认编译通过（本地 `local.properties` 填入测试值）
- [ ] 1.6 **生产环境完成一次真实凭据轮换**：在 broker 侧改掉这组硬编码值本身，不能只改代码引用方式——旧凭据已经在 git 历史里，必须视为已泄露
- [x] 1.7 更新 `CLAUDE.md` "Secrets are injected at build time" 一节，把这两个新凭据加进说明

## 2. LAN 接口鉴权

> **2026-08-14 前置警告（实施 §1 时发现，已登记为 `docs/重构方案.md` 8.4）**：
> `LoginHandler` → `LoginUser.loginUser()` → `db.isUserExist()` 查的是 `User` 表，
> 而 `DaoHelp.createAdminUser()`（`:304`）在首次初始化时把该表播种为 **`admin`/`admin`**。
> 即出厂设备的 LAN 登录接口接受默认口令。
>
> **token 的强度不会超过签发它的那道校验。** 若默认口令未处理，本任务组做完的结果是
> 「用 admin/admin 换一个合法 token，然后畅通无阻」—— 看起来加了鉴权，实际没有。
> 处理默认口令属行为变更，需产品侧先定方案（首次启动强制改密 / 产线逐台写随机口令），
> 必须排在本组之前或与本组同批落地。

- [ ] 2.0 **前置（不可跳过）**：确认默认口令 `admin`/`admin` 的处理方案已定案并落地，否则本组的 token 机制无实际防护价值
- [ ] 2.1 `LoginHandler` 校验成功后生成随机 token（`UUID.randomUUID()`），存入内存 `Map<token, 过期时间>`，响应体带上该 token
- [ ] 2.2 新增一个校验中间件/过滤器，供 `/alarm`、`/oilDischarge`、`/baseOilcan`、`/upload` 复用：检查请求头 `X-Auth-Token`，缺失或过期直接拒绝
- [ ] 2.3 过期时间设为可配置常量（默认建议 24 小时，具体数值找产品侧确认）
- [ ] 2.4 跑 `assembleDebug` + 单测，确认通过
- [ ] 2.5 真机验证：用 PC 客户端（或 curl）实测"未登录直接调接口被拒绝"和"登录后携带 token 正常访问"两条路径
- [ ] 2.6 通知 `device-write-back-sync` 变更：其 tasks.md 第3组"暂时沿用零鉴权"的临时状态可以结束，写接口接入本任务组产出的校验中间件

## 3. 报警上报诚实性修正

> ## ⏸ 本任务组暂缓，等产品侧回答一个问题（2026-08-14，3.1 的执行结果）
>
> 3.1 的结论有两条，第二条推翻了本组的前提：
>
> **结论一（3.1 原本要问的）：改成如实返回是安全的。** `MainActivity` 的 4 处调用
> （`:484`、`:487`、`:839`、`:902`）全部是裸语句，返回值没有被赋值、判断或传递。
> 不存在死循环重试或阻塞式弹窗这类未预期分支。
>
> **结论二（更重要）：这条链路根本不通，修完仍然不通。** 全项目仅有的两处 `bindService`
> 都在 `SocketManager.java:38, :40` 被注释掉，`iBackService` 恒为 `null`，
> `SocketManager.sendAlarm()` 每次在 `:66` 返回 `false` 而从不调用 `BackService`；
> 也没有任何 `startService(BackService)`，所以 `initMqttClient()` 永不执行。
> 另一条出口 `DBDataManager.sendOilMsg()`（`:868`）是个空方法体。详见 `docs/重构方案.md` 8.5。
>
> **需产品侧回答**：MQTT 上报是本来就该关，还是被误注释掉了？
> - 本来就该关 → **本组作废**，正确动作是删除 `BackService` + AIDL + `SocketManager` 相关方法
> - 被误注释 → **现场报警当前没有上云**，这是正在发生的线上问题，优先级高于本组；
>   需先恢复绑定并真机验证，再回来做诚实返回值
>
> 在这个问题有答案之前做 3.2–3.8，无论哪个答案都是白做。

- [x] 3.1 **前置验证（不可跳过）**：找到 AIDL 接口 `IBackService.sendAlarm()`/`sendUnloadOil()` 的全部调用方代码，读一遍它们目前如何处理返回值，确认改成如实返回不会触发调用方未预期的分支（比如死循环重试、阻塞式弹窗）—— 已完成，结论见上方横幅
- [ ] 3.2 新建本地持久化队列表（复用现有 `DaoHelp`/`DbUtils`），字段包含消息类型、payload、创建时间
- [ ] 3.3 `sendAlarms`/`sendUnloadOils` 改为：发布成功返回 `true`；`MqttException`/`MqttPersistenceException`/未连接时返回 `false` 并写入本地队列，不再无条件返回 `true`
- [ ] 3.4 在现有重连检测逻辑（`onReloadMqtt`/`notifyOiline` 触发处）里加入队列补投：连接恢复后依次尝试重新发布队列中的消息，成功则删除，失败保留
- [ ] 3.5 给队列加失效期（具体天数与 `device-write-back-sync` §5.3 云端队列口径对齐，找产品侧确认），超期未投递的记录标记失败并保留供人工排查，不静默清除
- [ ] 3.6 跑 `assembleDebug` + 单测，确认通过
- [ ] 3.7 补针对性单测：断连时返回false且入队、连接恢复后队列被清空、发布异常时不崩溃且入队
- [ ] 3.8 真机/真实 broker 验证：手动断开网络触发一次报警，确认进队列；恢复网络，确认自动补投成功

## 4. 收尾

- [ ] 4.1 更新 `docs/重构方案.md` 4.5.1，把"待决策"标记为已解决，记录本变更采用的方案（诚实返回值+本地队列补投）
- [ ] 4.2 更新 `docs/小程序与PC端集成设计.md` §3.3、§6 风险清单，标注 LAN 鉴权、MQTT 凭据两项已完成
- [ ] 4.3 更新 `CLAUDE.md`，记录新增的鉴权机制供后续开发者了解
