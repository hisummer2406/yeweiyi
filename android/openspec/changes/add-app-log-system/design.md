## Context

- 现场问题：多根旧中成探针偶尔出现持续几分钟、多个罐轮流触发的高温报警；新探针没有出现过，也不是插拔触发的
- 和代码对应的地方：
  - 温度只由 45 帧更新（`ZhongChengCompany.parseValidatedFrame` 的 17 字节分支），按帧内地址 `bytes[2]` 绑定到罐（issue #28 的修复）
  - 温度解码 `calcTemperature` 是无符号的 `raw/16`，**对结果不做合理性检查**
  - `AlarmDetection.beginDection` 只要一次 `temper > highTempAlarm` 就报警；直到下次读回正常值，本地标志复位才算恢复
  - `SerialPortManager.RequestThread` 每访问一个罐就问一次温度（`wenDuTurnPos` 按罐位顺序推进），所以一段时间内旧探针都回异常值时，表现就是"轮流报警"（推断）
- 待验证的嫌疑：旧探针回了 CRC 正确但温度值异常的帧（错误码、复位值、负温度被无符号解码）、地址冲突、帧拼接时被误切。**日志要能区分这几种情况**
- 现有设施和不足见 proposal.md「Why」

## Goals / Non-Goals

**Goals:**
- 一套可复用的日志体系，以后加功能只需新增模块枚举、调用 `AppLog`
- 按级别控制日志量：默认记 W，现场按需调到 E/I/D；以后可以由云端针对指定设备调整
- 现场开到 W、复现、导出 zip 发回后，能判定高温误报来自哪一帧、哪种原因
- 日志不写满磁盘（总计 ≤200MB），不拖慢串口轮询，不泄露凭据

**Non-Goals:**
- 修复高温误报本身
- 云端下发级别、远程拉取日志、导出到 U 盘
- 改动 `ProbeTrafficRecorder`
- 把现有的 `Log.x` 调用批量改写成 `AppLog`；把已有 Java 类改写成 Kotlin

## Decisions

### 1. 分两层：纯逻辑核心 + Android 接入层，新增类全部用 Kotlin

- `logic/applog/`（不引用 `Context`/`android.*`，写 JVM 单测）：
  - `LogTypes.kt`：`LogModule`、`LogLevel`、`LogClock`、`LogRecord`
  - `LogConfig`（容量参数）、`LogFormatter` + `LogRedactor`（格式和脱敏）、`RepeatSuppressor`（重复合并）
  - `LevelPolicy`（生效级别计算：开关、按模块覆盖、DEBUG 到期）
  - `RollingFileSink`（切分、封顶、保留期、剩余空间检查）、`AsyncLogWriter`（有界队列、丢弃计数、刷盘）
  - `KeyedRateLimiter`、`TempAnomalyDetector`、`FrameRing`、`ProbeCommStats`（高温误报埋点用）
  - `LogArchiver`（`java.util.zip` 打包）
- `applog/AppLog`（`object`，对 Java 调用方用 `@JvmStatic`）：初始化、级别持久化（SharedPreferences）、会话头、导出、清空
- `applog/ProbeLog`、`applog/AlarmLog`（`object`）：**领域日志**，每个业务领域一个，把“记什么、记哪一级”集中在一处，业务代码只调一个方法；以后新增领域照此新建 `XxxLog`（见 docs/日志体系.md §2）
- 高级设置底栏「导出日志」按钮（`applog/LogExportUi`，点击导出、长按进 `fragment/LogSetFragment`）。最初放在设置内容区第三行，真机上被裁掉，2026-10-17 改到底栏
- 构建：Kotlin 插件 1.7.22，`jvmTarget 1.8`。只用 API 21 可用的 JDK 类（不用 `java.nio.file`、`java.util.function`）
- 理由：沿用 `logic/inout`、`logic/thingsboard` 的做法，可测试的部分和 Android 依赖分开；用户要求新增类用 Kotlin

### 2. 存储位置和容量参数

| 参数 | 值 |
|---|---|
| 目录 | `filesDir/logs/`（不用 `cacheDir`：系统空间不足或用户清缓存时会被删掉，偏偏是出问题最需要日志的时候） |
| 文件名 | `<module>-yyyyMMdd-HHmmss-NN.log` |
| 单个文件上限 | 2MB |
| 模块上限 | PROBE 80MB；ALARM/CLOUD/STOCK/SYSTEM 各 30MB（依据见 docs/日志体系.md §5） |
| 总上限 | 200MB |
| 保留期 | 30 天 |
| 剩余空间下限 | 200MB，低于时暂停写入 |

新建文件时为它预留一个文件的上限，保证文件写满后总量也不超过上限。

### 3. 级别过滤

**各级别记什么**（各模块都按这个约定）：

| 级别 | 记什么 | 估计量（推断） |
|---|---|---|
| E | 失败、异常 | 接近 0 |
| W | 异常数据、异常状态 | 正常不到 1MB/天（一次 5 分钟、4 个罐的误报约 0.2MB） |
| I | 状态变化、定时摘要 | 约 0.5MB/天 |
| D | 逐次收发的明细 | 约 50–60MB/天（24 小时到期前约 60MB，PROBE 80MB 能完整保留） |

**生效级别**（`LevelPolicy`，纯逻辑）：

| 状态 | 生效级别 |
|---|---|
| 默认（从未设置） | 开关打开、WARN |
| 「日志记录」开关关闭 | ERROR |
| 开关打开 | `modules[模块] ?: level`，刚打开时 `level = WARN` |
| 生效级别是 DEBUG 且 `now ≥ debugUntilMs` 或 `now < setAtMs`（时钟被往回调） | 退回 WARN，持久化并记一条级别变更 |

- 持久化字段：`enabled`、`level`、`modules`（按模块覆盖）、`debugUntilMs`、`source`（LOCAL/CLOUD）、`setAtMs`
- 统一设置接口 `AppLog.applyLevel(enabled, level, modules, debugDurationMs, source)`：后设置的生效。本地设置页只设默认级别，DEBUG 时长固定 24 小时
- 云端以后的接入方式（不在本次范围）：推荐 ThingsBoard 共享属性 `logLevel`，形如 `{"level":"W","modules":{"PROBE":"D"},"durationSec":86400}`，解析后调用同一个接口，`source=CLOUD`；字段名要先和云端确认（CLAUDE.md：协议未冻结）
- 调用方线程上的判断：`AppLog.isLoggable(module, level)` 只读一个 volatile 快照，不满足时直接返回，不拼字符串。到期检查放在写线程和设置页上，不放在调用方的热路径里
- logcat 输出和文件用同一套过滤

### 4. 不受级别限制的事件

只有两类：崩溃（`AppLog.crashSync`，同步写入）和日志体系自身的事件（`AppLog.critical`，`internal`，固定写 SYSTEM：会话头、级别变更/到期、导出、清空）。**业务事件一律走级别策略**，重要的（包括报警产生/恢复）记 E——默认级别是 W，E 一定会被记录，同时现场可以通过级别统一控制。ERROR 级别和上述系统事件写入后立即 `flush` + `FileDescriptor.sync()`；系统事件不参与重复合并。

### 5. 写入线程和刷盘

- 有界队列，容量 2000；队列满时丢弃并计数，下一次写入时补一条 `日志丢弃 count=N`
- 普通日志每 1 秒或攒够 64 条刷一次；ERROR 级别和系统事件立即 fsync
- 崩溃不走队列：在 `CrashAppLog.uncaughtException` 里同步写入 `SYSTEM` 模块，写完再交给原有流程；`/sdcard/crashLog` 原有输出保留
- 理由：`SerialPortManager.RequestThread` 的时序直接影响 RS-485 收发（issue #28），不能在串口线程上做文件 IO

### 6. 行格式和调用封装

```
2026-10-17 14:30:05.123 [PROBE][W] 温度异常 pos=3 addr=05 hi=FF lo=F0 raw=65520 decoded=4095.0 final=4095.0 prev=18.5 reason=jump frame=FEFE05...
2026-10-17 14:30:05.124 [ALARM] 高温报警 pos=3 addr=05 temp=4095.0 threshold=60.0 corr=0.0
2026-10-17 14:30:07.002 [SYSTEM][E] 导出失败 path=... err=...
```

- 时间、模块名、级别标记由 `LogFormatter` 统一生成，调用方只传事件名（中文短语）和成对的 key/value
- 调用方式：`AppLog.w(LogModule.PROBE, "温度异常", "pos", pos, "raw", rawVal)`；`AppLog.e(LogModule.SYSTEM, "导出失败", throwable, "path", path)`
- 会话头（系统事件）：`会话开始 version=... deviceId=... wallClock=... uptimeMs=... level=W source=LOCAL`
- 理由：时间在最前面，按时间对齐多个模块的文件最方便；`key=value` 便于以后写脚本筛选

### 7. 脱敏、重复合并

- `LogRedactor`：键名包含 `token`、`password`、`secret`、`pwd` 的项（不区分大小写）替换成 `***`，在格式化阶段统一处理
- 重复合并：同一模块、同一级别、同一内容在 10 秒内连续出现时只写第一条，窗口结束或内容变化时补一条 `重复 count=N`

### 8. 导出和清空

- 导出：先对写入线程发一个刷盘屏障，然后打包 `logs/`、`/sdcard/crashLog/`（如果存在）、`device-info.txt`，以及按时间合并的 `all.log`（`LogMerger` k 路归并，边读边写进 zip，续行跟着主行走），输出到 `Environment.getExternalStorageDirectory()/yeweiyi-log-<设备编号>-yyyyMMdd-HHmmss.zip`（先写 `.tmp` 再改名）
- `targetSdk 27` 下写根目录走旧存储权限，和现有的 Excel 导出同一条路径
- 设备编号来自 `ThingsBoardCredentialStore.load()` 里的 deviceId，没有就用 `unknown`
- `device-info.txt`：versionName/versionCode、Android 型号/SDK、剩余空间、级别设置（含来源、DEBUG 到期时间）；每个罐的罐位、探针地址、探头类型、高温阈值、温度补偿、其余报警阈值
- 清空：在写入线程上关闭当前文件、删除全部日志文件，再写一条系统事件 `日志已清空`

### 9. 高温误报埋点（第一个使用方）

原则：**逐帧数据默认只进内存；W 只写异常和报警前报文，I 加摘要，D 才逐帧落盘。**

| 位置 | 级别 | 内容 |
|---|---|---|
| `SerialPortManager` 收到应答后 | D | `收发 pos kind=44/45/42 result=ok/bad/noResp frame=...`；PROBE 生效级别 ≤W 时同时压入 `FrameRing`，≤I 时更新 `ProbeCommStats` |
| `ZhongChengCompany` 17 字节 45 分支 | W | 交给 `TempAnomalyDetector`，命中时写 `温度异常 pos addr hi lo raw decoded corr final prev reason frame`，并增量写出最近报文 |
| 同上，帧内地址匹配不到罐 | W | `温度异常 reason=noMatch addr frame`，按地址限频 |
| 同上，每次启动后每罐第一帧 | I | `温度首读 pos addr raw decoded final` |
| `AlarmManager.addAlarmState`（`AlarmLog`） | E | `报警产生 pos type value`，按（罐位，类型）10 分钟限频，带 `skipped=N` |
| `AlarmDetection.tempHeightDection`（`AlarmLog`） | E | `高温报警 pos addr temp threshold corr`；PROBE ≤W 时增量写出最近报文 |
| `AlarmDetection` 高温恢复分支（`!isTemperAlarm[pos]` 复位处，`AlarmLog`） | E | `高温恢复 pos temp` |
| 定时（每 5 分钟） | I | 每罐一行 `通讯摘要 pos temp oilLevel errorTimes ok bad noResp`，取完清零 |

`TempAnomalyDetector` 的规则：

| 条件 | `reason` | 说明 |
|---|---|---|
| 和上一次相比跳变 ≥ 5℃ | `jump` | 5℃ 是推断值：罐内油温变化很慢，两次读数只隔几秒。恢复帧同样满足，能看出异常持续了多久 |
| 进入超阈值状态 | `overLimit` | 阈值为 0 时不判，与 `AlarmDetection` 口径一致 |
| 从超阈值状态回落 | `backUnderLimit` | 带上持续超阈值的帧数；持续期间不逐帧记录 |

- 报文写出（`ProbeLog.dumpRecentFrames`）：触发来源是温度异常、高温报警、液位类报警（高/高高/低/低低/高水位）、探头断线。**增量写出**：`FrameRing` 每帧带序号，每罐记下上次写到哪一帧，只写之后的新帧（最多 30 帧），首行带 `since=上次写到的时间`，没有新帧时 `count=0`，有帧被挤出缓冲时 `gap=true`。几个报警挨着发生时不会重复。限频：每罐每 10 分钟最多写出 10 次（`WindowRateLimiter`），超出的记 `skipped=N`，防止液位在阈值附近来回跳时量接近 D 级
- `addAlarmState` 要限频：探头断线期间，`SerialPortManager` 的 handler 在每次轮询失败时都会调用它（`SerialPortManager.java` 中 `handler` 的 `handleMessage`）
- `reqKind` 要在 `SerialPortManager` 里用局部变量记下来：发 42 之前 `askChangDu` 已经被置成 false，事后判断不出来；42 应答的 `isRight` 恒为 false（`ZhongChengCompany` 14 字节分支），不计入 `bad`

### 10. `LogCatHelper` 的处理

先在真机上看 `/sdcard/Android/com.sansen.yeweiyi/*.log` 的实际内容：
- 如果是空的，或者只有它自己的错误输出：停用（删掉 `MyApplication` 里的 `start()` 调用），类本身保留，等确认没有用处再删
- 如果确实有内容：保留，导出时一起打包
- 两种情况都不在这次修复它

## Risks / Trade-offs

| 级别 | 风险 | 应对 |
|---|---|---|
| 中 | 埋点改变了串口轮询时序，引发新的通讯问题 | 轮询线程上只做级别判断、内存操作和入队，不做 IO；真机对比 E/W/D 三种级别下的 `errorTimes` 和刷新频率 |
| 中 | 引入 Kotlin 后构建依赖网络（插件、标准库），离线环境首次构建会失败 | 版本固定为 1.7.22；本机已验证能下载和编译 |
| 中 | 异常的形态不是"跳变"或"超阈值"（例如温度缓慢爬升到阈值） | 任何触发高温报警的帧都会经过 `overLimit` 判定；报警时 dump 前 30 帧 |
| 低 | 现场开了 D 级后忘记关 | 24 小时自动退回 W；200MB 总上限兜底 |
| 待确认 | 跳变阈值 5℃ 是推断值 | 放在常量里；第一批现场日志回来后按实际数据调整 |

## Migration Plan

纯新增，没有数据迁移。回滚就是去掉 `MyApplication` 里的初始化调用；`filesDir/logs` 里留下的文件不影响其他功能。

## Open Questions

- 日志诊断页的英文、西班牙文是 AI 翻译，待文案 review
- 云端下发的字段名和格式，要和 `yeweiyi-cloud` 对齐
