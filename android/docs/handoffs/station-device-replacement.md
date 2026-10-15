# 交接：大屏出厂码与 TB 设备身份一致、换机不继承旧数据/补投队列（#45 / cloud#61）

**状态：待真机验收。** 补丁已在隔离分支验证（JVM + APK），未提交、未合入、未推送 PR #38，未刷机。不宣称整体完成。

- 本文件与补丁副本是 `main`（2a2e0ee）工作区里的**未跟踪文件**，与补丁基线无关。
- 契约：yeweiyi-cloud `docs/station-device-replacement-contract.md` v1.2（当前文件 SHA256 `15fc109506670d6a…`，v1.2 正文含 §8.1/§14；先前核对的 `4b9138c8…` 是同一版本号的更早内容，云端后来又编辑过；对安卓无新增要求）。
- 依赖：**PR #38 未合并**（OPEN，HEAD `df98227`，最后更新 2026-10-09），main 无任何 ThingsBoard 代码。

## 1. 基线与补丁

| 项 | 值 |
|---|---|
| 基线 | `origin/feature/thingsboard-device-protocol`@`df982279522ae0b8d9f67cc3a88265aac818ac3f`（今日 fetch，无漂移；`gh pr view 38` 的 headRefOid 一致） |
| 补丁 | `docs/handoffs/issue45-identity-isolation.patch`，SHA256 `350596eefce86f4baa30559220e383a9ae7d12a51f5a2f02223f51ca9d511f55`，`git apply --check` 干净 |
| 原 worktree（保留，未动） | `/private/tmp/claude-501/-Users-stone-Developer-zhongchengyeweiyi/b3969242-dec5-4a37-8141-5901f5584ce2/scratchpad/wt`（detached@df98227，补丁改动未提交） |
| 本轮验证 worktree/分支 | `/private/tmp/claude-501/-Users-stone-Developer-zhongchengyeweiyi/b3969242-dec5-4a37-8141-5901f5584ce2/scratchpad/wt2`，本地分支 `verify/issue45-identity-isolation`（自 df98227，补丁已应用、**未提交**；`git diff` 与补丁逐字节一致） |
| 测试 APK | `/private/tmp/claude-501/-Users-stone-Developer-zhongchengyeweiyi/b3969242-dec5-4a37-8141-5901f5584ce2/scratchpad/wt2/app/build/outputs/apk/debug/app_1.0.5-A_2026-10-14_debug.apk`（debug，SHA256 `1468230ae9765a404a738f90dbf733612712bef62244dfa509ae0526000b2ae6`） |

## 2. 本轮实际检查

环境：本机仅 JDK 17，Gradle 6.7.1 需 JDK 11；用 scratchpad 内 Temurin 11.0.32（未改系统）；worktree 的 `local.properties` 加了 `ndk.dir`（仅 scratch 副本）。

| 命令（wt2） | 结果 |
|---|---|
| `./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest :app:assembleDebug` | exit 0。读取 `app/build/test-results/testDebugUnitTest/*.xml`：**312 例，0 失败，0 错误，7 跳过**，时间戳为本次运行（非 UP-TO-DATE）；`IdentityIsolationTest` 5/5、`TelemetrySnapshotBuilderTest` 9/9、`PendingAlarmStoreTest` 13/13 |

**7 个跳过 = `ProbeReplayTest` 的 `Assume.assumeFalse`**：现场探头数据未采集（`manifest.tsv` 无条目，tasks.md §1.6）。**不算通过**，有效通过 305。未删除、未伪造任何数据。与本补丁无关，PR #38 上本就如此；补数据需另用 `sansen.probeRecording` 的采集版在现场录制，不是本次验收前置。

**测试 APK 的构建限制：** 本机 `local.properties` 没有任何 `sansen.*` 项，故该 APK 的 `PROVISION_DEVICE_KEY/SECRET` 为空（自动领取会显示“本机未配置注册密钥”）、TLS 关闭。要自动领取或走 TLS，需用测试实例的 key/secret 重新构建（见 §5 前置）；否则走“手填令牌”兜底路径。

## 3. 五项确认（JVM 层与代码证据；运行时行为均待真机）

| 确认项 | JVM/代码证据 | 真机 |
|---|---|---|
| 新设备码与凭据独立 | 编码=Provisioning deviceName=MQTT clientId（`ThingsBoardManager.provisionAccessToken`、`ThingsBoardMqttConnection.connect`）；换编码且令牌框仍为旧令牌时旧令牌作废（`ThingsBoardSetFragment.save`）；token 经 Keystore 加密，编码仅标识 | 待 |
| 旧历史与待补投队列不以新身份发送 | `IdentityIsolationTest`：切换身份后队列为空、旧文件内容保留；新事件不落入旧身份文件；ACTIVE 报警不在新身份下补“恢复”。生产路径 `bindIdentity` 在连接/补投前调用；改设置时“先断开再切身份” | 待（文件改名、Keystore、MQTT 顺序 JVM 测不到） |
| 同设备断网恢复按原身份补投 | 编码不变 → `bindIdentity` no-op，同一文件；`PendingAlarmStore.drainAndSend` 逐条成功才删（`PendingAlarmStoreTest`）；`IdentityIsolationTest.sameIdentityRebindResumesItsOwnQueue` | 待 |
| 多罐编号与本机显示一致 | 快照 `tank{N}` 的 N = `NetOilCan.id` = `OilCan.getOilCanPos()`（`OilcanDataManager:442`，本机罐位号）；客户端属性同取 `getOilCanPos()`（`ThingsBoardManager.currentTankAttributes`）；非连续启用罐号由 `TelemetrySnapshotBuilderTest` 覆盖 | 待（需对照本机罐显示） |
| 配置复制/备份恢复/身份修改不串数据 | 修改编号：同上，隔离。复制/恢复到另一硬件：token 密文解不开 → 不自动领取（`hasUnreadableToken`，`TOKEN_UNREADABLE`）；队列按编码分文件，旧编码文件不被别的编码读到 | 待（Keystore 解密失败路径只能真机） |

已知取舍：同一硬件输错编码再更正，错码期间排队的事件留在错码名下，不自动转移。

## 4. 最短真机验收清单

**设备：** 最少 **1 台**真机大屏即可覆盖 B–E（用同一台依次改码 A→B→A）；**第 2 台**只在需要 A/B 并行（A）和跨硬件恢复（F）时用，建议两台都上。固件：已在分支文档中验证过的 rk3562_t / Android 13；要求 Android ≥ 6.0（Keystore）。安装用上面的 debug APK，**先卸载旧包再装**（签名不同，卸载同时清空 Keystore 密钥）。debug 包可 `run-as`。

**测试 TB：** 只用云端的隔离测试实例，**不连生产**（APK 默认 broker 是生产形态 IP，务必在“高级设置 → 云平台设置”改成测试实例 host:MQTT 端口）。请云端提供：可从大屏所在网络访问的 MQTT host:port、是否 TLS、provision key/secret（或预先生成的 token）。需要 **2 个预注册设备**（Check for pre-provisioned，Device Profile 开启 Provisioning），名称带前导零，例如 `ZCT000901`（A）、`ZCT000902`（B）。

**A/B 数据：** 本机配置 ≥2 个启用罐，罐位号取非连续（如 1 和 3），两罐读数明显不同；报警事件需真实探头数据，**演示模式（TEST_DATA）下报警不上报**，所以把某罐高液位报警阈值调到低于当前液位触发报警；出入库按现场操作产生一条记录。

**日志采集：**
- `adb logcat -v time -s ThingsBoardManager TBMqttConnection TBCredentialStore TBIdentityFile TBCaStore`（身份切换日志含“设备身份变更: A -> B”；备份恢复路径含“已保存的 token 无法解密”）。
- 设备文件日志：`/sdcard/Android/com.sansen.yeweiyi/*.log`。
- 队列文件：`adb shell run-as com.sansen.yeweiyi ls -l files/` 与 `cat files/thingsboard_pending_alarms_<hex>.json`（`<hex>` = 编码 UTF-8 字节十六进制，如 `ZCT000901` → `5a4354303030393031`）。
- TB 侧：两个设备的 Latest telemetry、Telemetry 时间戳、设备 Last activity；每步截图或导出。

| # | 步骤 | 预期 | 通过判据 |
|---|---|---|---|
| A | 设备1 配置为 `ZCT000901`，保存、领取/手填 token、连上 | 日志“设备注册结果…SUCCESS”或手填连接成功；TB 中 `ZCT000901` 有 `tank1.*`/`tank3.*` 遥测；`ZCT000902` 无数据 | 实体名与输入完全一致（前导零保留）；罐号与本机显示一致 |
| B | 断开设备1网络，产生一次报警 + 一次出入库 | `files/thingsboard_pending_alarms_5a43…3031.json` 有 ≥2 条 | 文件存在且条目数符合 |
| C（同设备补投） | 编码不变，恢复网络 | 日志补投；TB 的 `ZCT000901` 收到报警/出入库；队列文件清空；按序（产生在前、恢复在后） | 只在 `ZCT000901` 下出现，无重复 |
| D（换机隔离） | 重做 B（断网入队，**不要联网**），然后把编码改为 `ZCT000902`（配新 token），保存并联网 | 日志“设备身份变更: ZCT000901 -> ZCT000902”；`ZCT000902` 只出现新采集的快照，**不出现** B 步的报警/出入库；`ZCT000901` 的队列文件仍在盘上 | `ZCT000902` 无旧事件；旧文件未被删除或发送 |
| E（旧队列不串入/改回） | 把编码改回 `ZCT000901` 并联网 | 旧队列在 `ZCT000901` 名下补投，`ZCT000902` 不收到 | 事件只落在原身份 |
| F（备份恢复/复制配置到另一硬件） | 把设备1 的 `shared_prefs/config.xml` 与 `files/thingsboard_*.json` 经 `run-as` 拷到设备2（应用先停；Android 13 `adb backup` 已不可用，用此法等价模拟），启动设备2 | 设备2 日志“已保存的 token 无法解密，不自动领取”；设置页显示“已保存的令牌无法解密…请核对设备编号后重新保存”；**不连接**旧编码；TB 的 `ZCT000901` 无来自设备2 的连接 | 无冒用旧编码；在设备2 改录 `ZCT000902` 后只发自己数据 |
| G（多罐） | 检查 A 步 TB 中罐键与本机罐位号逐一对照；禁用一个罐后看变化 | 键名 `tank{位号}` 与本机一致；禁用罐的 ACTIVE 报警被恢复 | 无串罐 |

**哪些现在就能用测试 APK 验证：** A–G 全部（在隔离 TB 实例上），不需要 PR #38 合并。
**必须等前置条件：**
- 补丁并入 PR #38（或 main 合并 PR #38）：只影响“能否发布给现场”，不影响上述测试；契约 §9「安卓 B 只发自己数据」要在合入后才对发布版成立。
- 自动领取（A 步的 Provisioning 路径）：需云端提供测试实例 provision key/secret 并重构建 APK；没有则用手填令牌，此时 A 的领取失败路径无法覆盖。
- 员工按罐授权、扫码只导航：属 cloud#63，与安卓无关。
- ProbeReplayTest 的 7 例：需现场探头采集数据，非本验收前置。

## 5. 未完成项（全部待真机 + ThingsBoard）

A–G 全部未执行。JVM 与编译只证明存储类逻辑和可编译，文件改名、Keystore 失败路径、MQTT 补投顺序均未在真机验证。
