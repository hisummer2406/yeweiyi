## 0. 构建

- [x] 0.1 根 `build.gradle` 加 `kotlin-gradle-plugin:1.7.22`，`app/build.gradle` 加 `kotlin-android` 和 `jvmTarget 1.8`；`:app:compileDebugKotlin` 已通过（本机 `~/.gradle/gradle.properties` 里配了失效的代理，构建时需要加 `-Dhttp.proxyHost= -Dhttps.proxyHost=` 覆盖）
- [x] 0.2 IDE：Android Studio 4.2 自带 Kotlin 插件 1.5，打开会标红；改用 Panda 1（2025.3.1 Patch 1）后不标红、可正常编译安装（用户 2026-10-17 确认）。`app/build.gradle` 补了 `compileOptions` 1.8，与 `jvmTarget` 一致

## 1. 纯逻辑核心（`logic/applog/`，Kotlin，先写单测再写实现）

- [x] 1.1 `LogTypes.kt`（`LogModule`/`LogLevel`/`LogClock`/`LogRecord`）、`LogConfig`（design.md 决策 2 的表）
- [x] 1.2 `LogFormatter` + `LogRedactor` 单测：行格式 `时间 [模块] 内容`（毫秒时间在最前、W/E 级别标记、INFO/DEBUG 无标记）、事件名 + 成对 key/value 拼接、key/value 个数为奇数时不抛异常、凭据类键名掩码（大小写不敏感）
- [x] 1.3 `RepeatSuppressor` 单测：窗口内合并、窗口结束或内容变化时补 `重复 count=N`、系统事件（critical）不参与合并
- [x] 1.4 `LevelPolicy` 单测：开关关闭时为 ERROR、打开后默认 WARN、按模块覆盖、DEBUG 到期退回 WARN、时钟往回调视为到期
- [x] 1.5 `RollingFileSink` 单测（临时目录 + 注入时钟和剩余空间）：按大小切换、模块上限、总上限、保留期、低于空间下限时暂停及恢复、目录不可写时不抛异常且只告警一次、首次打开续写未满文件
- [x] 1.6 `AsyncLogWriter` 单测：队列满时不阻塞并补 `日志丢弃 count=N`、critical 记录立即落盘、刷盘屏障、清空、同步写、多线程并发写入
- [x] 1.7 `TempAnomalyDetector` + `KeyedRateLimiter` 单测：每罐首帧只作基准、`jump`（≥5℃，含恢复帧）、`overLimit` 只在进入时记、`backUnderLimit` 带持续帧数、阈值为 0 时不判；限频及 `skipped=N` 计数
- [x] 1.8 `FrameRing` + `ProbeCommStats` 单测：每罐独立、超出容量覆盖最旧、dump 按时间顺序；计数累加，取摘要后清零
- [x] 1.9 `LogArchiver` 单测：打包目录和附加文本，zip 能解开且内容一致
- [x] 1.10 `./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest`，记录实际通过数 —— 2026-10-17：全量 360 个，0 失败，7 个 skipped（原有 ProbeReplayTest）；2026-10-17 加 all.log 后 365 个，0 失败

## 2. Android 接入层（Kotlin）

- [x] 2.1 `applog/AppLog`：`d/i/w/e(module, 事件名, key, value, ...)`、`e` 带 `Throwable` 的重载、`critical(...)`（internal，只给日志体系自身用）、`isLoggable(module, level)`；初始化放在 `MyApplication.onCreate()` 最前面（在 `CrashApphandler` 之前）；初始化之前的调用只输出 logcat
- [x] 2.2 级别持久化（SharedPreferences）+ 统一设置接口 `applyLevel(enabled, level, modules, debugDurationMs, source)`；DEBUG 到期检查在写线程上定期执行；级别变更记系统事件
- [x] 2.3 会话头：versionName/versionCode、deviceId、设备时间、开机运行时长、当前级别和来源
- [x] 2.4 `CrashAppLog.uncaughtException`：同步写 `SYSTEM` 模块再交给原有流程，`/sdcard/crashLog` 原有行为不变
- [x] 2.5 导出：刷盘屏障 → 生成 `device-info.txt` → 打包 → 写到根目录；后台线程执行，回调给主线程用 Toast 提示路径或失败原因
- [x] 2.5a 导出时附带按时间合并全部模块的 `all.log`（`logic/applog/LogMerger`，k 路归并、续行跟主行，边读边写进 zip；`LogMergerTest` 覆盖）；总上限改为 200MB（PROBE 80MB，其余各 30MB）
- [x] 2.6 `./gradlew :app:assembleDebug` 编译通过

## 3. 设置页

- [x] 3.1 新建 `fragment/LogSetFragment`（Kotlin）+ 布局：日志记录开关、级别选择（E/W/I/D 英文缩写，由代码生成不进资源；开关打开时才可选）、当前级别和来源、DEBUG 剩余时长、占用空间、导出按钮、清空按钮（带确认对话框）。**点击一律用 `setOnClickListener`，不要用布局里的 `android:onClick`**（见 CLAUDE.md "Known landmines"）
- [x] 3.2 `SetSeniorFragment` 入口：最初放内容区第三行，真机上被裁掉；改为底栏「导出日志」按钮（点击导出、长按进日志诊断页），内容区布局不变。默认级别同时改为开启 + W
- [x] 3.3 文案精简为 11 条，加到 `values/`、`values-en/`、`values-es/`；英、西按用户要求 AI 翻译，已登记工作簿「文案对照」并标 AI待review
- [x] 3.4 编译通过

## 4. 高温误报埋点（只加日志调用，不改判定）

- [x] 4.1 新建领域日志 `applog/ProbeLog`、`applog/AlarmLog`（Kotlin）：ProbeLog 持有 `FrameRing`/`ProbeCommStats`/`TempAnomalyDetector`/限频器，5 分钟摘要定时器
- [x] 4.2 `SerialPortManager.RequestThread`：用局部变量记下 `reqKind`（44/45/42）；收到应答后调用 `ProbeLog.onResponse(...)`
- [x] 4.3 `ZhongChengCompany` 17 字节 45 分支：调用 `ProbeLog.onTemperatureFrame(...)`（匹配到罐和匹配不到罐两种情况）
- [x] 4.4 `AlarmManager.addAlarmState`：调用 `AlarmLog.onAlarmRaised(...)`（E 级，限频）
- [x] 4.5 `AlarmDetection`：`tempHeightDection` 调用 `onHighTempRaised`，高温恢复分支调用 `onHighTempRecovered`
- [x] 4.6 核对：埋点前后，`ZhongChengCompany` 和 `AlarmDetection` 的返回值、写入字段、分支走向完全一致（逐个 diff 自查，现有测试覆盖不到这部分）
- [x] 4.7 编译 + 干净重跑单测，记录实际通过数 —— 2026-10-17：`assembleDebug` 通过；全量 360 个，0 失败，7 个 skipped

## 4b. code review 修复（2026-10-17，按用户选定的 7 条）

- [x] 4b.1 传给 `ProbeLog.onResponse` 的解析结果改为本次尝试的值（`parsedThisTime`），不再沿用循环外的 `isRight`
- [x] 4b.2 `AlarmLog` 的 10 分钟限频只对 `PRO_ERR_CONN` 生效，其他报警每次切换都记
- [x] 4b.3 `LogSetFragment` 复用 `MainActivity.logSetFragment` 一个实例；隐藏时停时钟回调，显示时刷新
- [x] 4b.4 导出前删掉根目录旧的 `yeweiyi-log-*.zip`，只留最新一份
- [x] 4b.5 `crashSync` 先 `drainSync()` 写完队列里剩下的日志，再写崩溃记录（`AsyncLogWriterTest` 覆盖）
- [x] 4b.6 导出/清空结果提示改用 `Toast.LENGTH_LONG`
- [x] 4b.7 失败提示不再显示内部错误信息，改为"操作失败，详情已写入日志"（三套语言）；清空失败也写一条 E
- [x] 4b.8 编译通过；全量 366 个，0 失败，7 个 skipped
- [x] 4b.9 开机首轮/手动解警后误记「高温恢复」（review 第 1 条，2026-10-17 真机导出发现 12 条报警日志里 11 条是误记）：`AlarmLog` 只给记过「高温报警」的罐记恢复；另加「手动解警」记录（`AlarmCoordinator.btn_jieJing`）
- [x] 4b.10 最近报文触发来源扩大到液位类报警（高/高高/低/低低/高水位）和探头断线；改为增量写出（不重复同一帧，带 `since`/`gap`），限频改为每罐每 10 分钟最多 10 次（`WindowRateLimiter`），`ProbeDiagSupportTest` 覆盖；全量 371 个，0 失败
- [x] 4b.11 新增 `applog/SoundLightLog`：`AlarmDetection` 4 处开铃、4 处关铃前记 `声光开启 by=`/`声光关闭 branch= stillAlarming=`，排查"变红不响铃"（只记录，不改声光逻辑；可疑点记入 docs/日志体系.md §8）
- 未处理（用户本次未选）：logcat 未脱敏、`TempAnomalyDetector.reset()` 无调用方——已记入 docs/日志体系.md §8

## 5. 文档

- [x] 5.1 新建 `docs/日志体系.md`：模块、级别约定、参数、如何新增模块、现场开启和导出步骤、云端接入预留
- [ ] 5.2 （除 `LogCatHelper` 结论外已完成，等 6.2）CLAUDE.md：加一条指向上述文档；补充"新增类用 Kotlin"和 Kotlin 插件版本；`LogCatHelper` 的处理结论（6.2）同步到 "App entry" 一节

## 6. 真机验证（判定完成之前不可跳过）

- [ ] 6.1 E、W、D 三种级别下，各罐刷新频率和 `errorTimes` 与改动前一致，不新增通讯故障报警
- [ ] 6.2 查看 `LogCatHelper` 的实际产物，按 design.md 决策 10 决定保留或停用
- [ ] 6.3 导出 zip 能从根目录拷出、能解开，`device-info.txt` 内容正确，`all.log` 按时间排序且多行内容完整；日志接近 200MB 时导出耗时可接受；没有设备编号时文件名用 `unknown`
- [ ] 6.4 W 级和 I 级各正常运行至少一天，核对实际日志量，校正 design.md 决策 3 的估算
- [ ] 6.5 设成 I 后重启，确认级别保持；设成 D 后把时钟调到 24 小时后（或临时调短时长），确认自动退回 W
- [ ] 6.6 开关关闭时制造一次高温报警（调低阈值），确认 `高温报警`/`高温恢复` 仍被记录；切到 W 再制造一次，确认 `温度异常` 和 `FrameRing` dump 出现
- [ ] 6.7 交付现场：打开日志记录（W）→ 复现 → 导出 → 收回日志，分析结论另外立项
