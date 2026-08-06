## 1. MQTT 凭据外置（最独立，最先做）

- [ ] 1.1 `local.properties.template` 新增 `MQTT_USERNAME`/`MQTT_PASSWORD` 占位项，照抄 `PRODUCT_KEY` 等现有条目的注释风格
- [ ] 1.2 `app/build.gradle` 新增读取逻辑，缺失时打印 `[配置缺失]` 警告，编译为空字符串（照抄现有四个凭据的模式）
- [ ] 1.3 `config/ApiConfig.java` 新增对应常量并加 javadoc 说明
- [ ] 1.4 `service/BackService.java` 里硬编码的 `userName="admin"`/`passWord="password"` 改为读取新常量
- [ ] 1.5 跑 `./gradlew :app:assembleDebug`，确认编译通过（本地 `local.properties` 填入测试值）
- [ ] 1.6 **生产环境完成一次真实凭据轮换**：在 broker 侧改掉这组硬编码值本身，不能只改代码引用方式——旧凭据已经在 git 历史里，必须视为已泄露
- [ ] 1.7 更新 `CLAUDE.md` "Secrets are injected at build time" 一节，把这两个新凭据加进说明

## 2. LAN 接口鉴权

- [ ] 2.1 `LoginHandler` 校验成功后生成随机 token（`UUID.randomUUID()`），存入内存 `Map<token, 过期时间>`，响应体带上该 token
- [ ] 2.2 新增一个校验中间件/过滤器，供 `/alarm`、`/oilDischarge`、`/baseOilcan`、`/upload` 复用：检查请求头 `X-Auth-Token`，缺失或过期直接拒绝
- [ ] 2.3 过期时间设为可配置常量（默认建议 24 小时，具体数值找产品侧确认）
- [ ] 2.4 跑 `assembleDebug` + 单测，确认通过
- [ ] 2.5 真机验证：用 PC 客户端（或 curl）实测"未登录直接调接口被拒绝"和"登录后携带 token 正常访问"两条路径
- [ ] 2.6 通知 `device-write-back-sync` 变更：其 tasks.md 第3组"暂时沿用零鉴权"的临时状态可以结束，写接口接入本任务组产出的校验中间件

## 3. 报警上报诚实性修正

- [ ] 3.1 **前置验证（不可跳过）**：找到 AIDL 接口 `IBackService.sendAlarm()`/`sendUnloadOil()` 的全部调用方代码，读一遍它们目前如何处理返回值，确认改成如实返回不会触发调用方未预期的分支（比如死循环重试、阻塞式弹窗）
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
