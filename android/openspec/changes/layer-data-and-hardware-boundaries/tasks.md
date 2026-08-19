> **任务顺序已按 `docs/重构方案.md` v2（2026-08-13）重排。** 原顺序是「4 个 Repository → 删 DBDataManager → 硬件边界 → factory/ 抽取」，
> 把 `factory/` 抽取放在最末尾。v2 把它提到最前，理由：它是「补测试」与「改结构」互相阻塞的解锁点，且它产出的是一套
> 可复制的迁移模板与一份真实耗时数据，应该用它来校准后面所有工作，而不是等一切做完再做。
> 同时补上原计划缺失的前置——第 1 组的 golden master 录制。原 §7.2 直接假设「现场抓取的真实报文」已存在，但没有任何任务产出它。

## 1. golden master：现场报文录制与重放基线（★ 全变更的前置，唯一依赖现场档期）

本组不改任何业务逻辑，只增加录制能力并采集数据。**没有这组产出，第 2 组的等价性验证无从谈起。**

> **2026-08-14 接缝更正**：原 1.1 指定的 `ProbeReciveDataListener.reciveData(byte[])`（`:339`）**不是探头数据路径**——
> 全项目无人调用 `setProbeReciveData`，该监听器恒为 `null`，其唯一调用点 `:327` 还位于一个进入即 NPE 的分支（见 1.4）。
> 真正的输入接缝在 `RequestThread.run()` 的 `:260–263`。且录 hex 字符串比录原始 `byte[]` 更合适：
> `probeInfoPase(String, IParseDataListener)` 吃的就是它，将来抽出的 `XxxParser` 吃的也是它，重放保真度更高。

- [x] 1.1 在 `manager/SerialPortManager` 的 `RequestThread.run()` 中，`data = DataUtils.bytesToHexString(bytes, bytes.length)`（约 `:263`）之后、分发给 `probeInfoPase` 之前加录制钩子：记录 hex 报文 + 时间戳 + `mOilCan.getProbeType()` + `mOilCan.getOilCanPos()`
- [x] 1.2 在 `IParseDataListener.hexDataParseFinish(OilCan)`（约 `:418`）加录制钩子：记录解析产出的 `OilCan` 全字段快照。已确认 `probeInfoPase` 在**同线程同步**回调本方法，因此与 1.1 的输入按线程内先后顺序配对即成立，无需额外关联 id
- [x] 1.3 录制开关默认关闭，经 `local.properties` 注入的 `BuildConfig` 标志启用（照抄现有四个凭据的注入模式），落盘到 `/sdcard/`
- [x] 1.4 把本次发现的缺陷登记到 `docs/重构方案.md` 第八节（**只登记不修**）：`ProbeReciveDataListener` 全项目零实现、`setProbeReciveData` 零调用，而 `:327` 对其无条件解引用；字段名 `isStopProbe` 语义反转（`stopProbeThread()` 置 `false`、`startProbeThread()` 置 `true`），故调用 `stopProbeThread()` 且串口仍打开时会走进该分支并 NPE
- [x] 1.5 跑 `./gradlew :app:assembleDebug`，确认编译通过
- [ ] 1.6 **现场采集（不可跳过；档期不由本地控制，应最早排期）**：正常工况跑满一天（覆盖昼夜温差，使温度字段拿到有意义的范围）；尽量制造报警工况（高位、低位、探头断线）；**每种在用的探头型号都要采到**——这一条决定 golden master 的覆盖面。注意本轮只覆盖探头轮询路径的三种 `probeType`（0 中诚 / 1 LD / 2 LD2），VR350 属 PC 通信路径，不在本轮范围（见 spec 说明）

> **2026-08-14 修正 1.8 的做法**：原文写「重放测试**此时测的是尚未改动的现有代码**」——**做不到**。
> `LDCompany`/`LD2Company`/`ZhongChengCompany` 三个解析器都 `import android.content.Context`、`SparseArray`，
> 且 `probeInfoPase` 内部调 `MyApplication.getInstance().getmOilcanDataManager()`，纯 JVM 跑不起来
> ——这正是 §2 要解决的问题本身，不能作为 §2 的前置。
>
> 但这不构成阻塞：**录制文件本身就是基线**。.tsv 里每一对 (输入报文 → 解析结果) 都是真实硬件在
> 真实工况下产生的，比在 JVM 上拿 mock 单例重放旧代码是更强的证据。所以基线以**数据**形式存在，
> 而非以「可执行的旧代码测试」形式存在；等价性断言在 §2.2 接上新 `XxxParser` 时才真正生效。

- [x] 1.7 建立 fixture 导入工具与重放骨架（不依赖现场数据，本机完成）：`tools/prepare-probe-fixture.sh` 把设备采集文件导入 `app/src/test/resources/goldenmaster/` 并在 `manifest.tsv` 登记探头型号/采集日期/工况；测试侧 `ProbeTrafficFixture`（IN/OUT 顺序配对）、`SnapshotComparator`（逐字段比对，默认零容差，排除 `lastTime`）、`FixtureManifest`
- [x] 1.8 用合成样本验证骨架自身可信：`GoldenMasterHarnessTest` 覆盖格式往返、非法行跳过、断电截断、IN 后接 IN 的无产出配对、比对器的差异定位与容差语义。**骨架不可信的话现场采集回来的数据也没有意义，而那时才发现的代价是重跑一次现场**
- [ ] 1.9 现场数据回来后：用 `tools/prepare-probe-fixture.sh` 逐份导入并登记；`ProbeReplayTest` 会自动从「跳过」转为实际校验（数据完整性、必须存在成功解析的样本、清单声明的探头型号确实出现在数据里）
- [ ] 1.10 跑 `:app:cleanTestDebugUnitTest :app:testDebugUnitTest`，确认 `ProbeReplayTest` 由 skipped 转为全绿
- [ ] 1.11 若现场档期迟迟无法落实，启用降级方案：依据厂商协议文档反推构造 fixture，并**在本任务下明确记录覆盖面缺口**（构造报文覆盖不了现场的畸形帧与时序异常），不得默认它等价于真实采集

## 2. factory/ 纯解析逻辑抽取（依赖第 1 组；本变更的模板验证批次）

- [ ] 2.1 为 `ZhongChengCompany`/`LDCompany`/`LD2Company` 各自抽取一个不依赖 `android.*` 的 `XxxParser` 类，承载字节级解析逻辑（9 个 `factory/` 文件中现有 6 个 `import android.*`）。**`VR350Factory` 本轮不做**——它是 `PCDataFactory`（PC 通信路径），不经探头分发 switch、不回调 `hexDataParseFinish`，第 1 组也采不到它的 fixture，没有等价性验证手段就不动它
- [ ] 2.2 把第 1 组的重放测试改为直接打在新抽出的 `XxxParser` 上，脱离 Android 运行时
- [ ] 2.3 抽取前后用同一批 fixture 报文跑输出 diff，**确认零差异后再合入**（对应 spec「抽取前后行为等价」；注意 `CLAUDE.md` "Known landmines" 记的负数温度、CRC 补零等边界）
- [ ] 2.4 原 `XxxCompany` 退化为薄适配层，只保留 `Context`/`SparseArray` 相关部分，内部委托 `XxxParser`
- [ ] 2.5 跑 `assembleDebug` + 全部单测（含新增），确认通过
- [ ] 2.6 **真机回归（不可跳过）**：确认现场读数与抽取前一致
- [ ] 2.7 记录本组的实际耗时，作为第 3–6 组工作量估算的校准依据
- [ ] 2.8 更新 `docs/重构方案.md`：把 v2 第 2 步标记为完成，并回填实测耗时

## 3. 交班领域（ShiftRepository）—— Repository 迁移的第一批，调用面最小

- [x] 3.1 新建 `ShiftRepository` 接口与实现，内部直接调用现有 `DaoHelp`/`DbUtils`（与 `DBDataManager` 当前访问的是同一套存储，不改表结构）——`repository/ShiftRepository.java`（接口）+ `repository/ShiftRepositoryImpl.java`（单例实现，`getInstance()`）
- [x] 3.2 **与原计划不同**：`DetailCommitWorkAdapter` 复核后不调用 `DBDataManager` 的任何交班相关方法（只是类名带 CommitWork，实际处理的是 `OilCanSet`），未改动。实际迁移的调用方是 7 个：`CommitWorkManager`（`commitWork(...)` 两个重载）、`CommitWorkRecortFragment`（`findAllObject`/`Sort`/`findLimit`/`delteRecord` 四处）、`PrintRunable`（`findLast`）、`JiLuFragment`（`delteAllInOilRecord`）、`DetailCommitWorkFragment`（`findObjectByID`）、`UpdateCommitDialog`（`findObjectByID`/`UpdateObject`）、`BackService`（`findLimitRecord`，MQTT `sendShifts`）。`stringToArray`/`getListToStringByGson` 调用点保持不变——它们是通用 Gson 帮助方法，不属于本次「交班数据读写」的迁移范围
- [x] 3.3 `DBDataManager` 中交班相关方法标记 `@Deprecated`（暂不删除）——`CommitWork(SparseArray,String)`、`CommitWork(int,String,SparseArray)`、`CommitWork(SparseArray)`、`CommitWorkStart(int,SparseArray)`、`saveCommitWorkRecord`（后者本就零调用方，一并标记）
- [x] 3.4 跑 `./gradlew :app:assembleDebug` 与 `:app:cleanTestDebugUnitTest :app:testDebugUnitTest`，确认通过——均 `BUILD SUCCESSFUL`；干净重跑读取 `app/build/test-results/testDebugUnitTest/*.xml`：81 个测试全部通过（0 failures / 0 errors），`ProbeReplayTest` 7 个 skipped 与本次改动无关（等待现场采集，见第1组）
- [ ] 3.5 **真机手工回归（不可跳过，尚未进行）**：本机无 adb/真机可用，提交交班、查看历史交班记录、编辑交班记录（`UpdateCommitDialog`）、删除交班记录、MQTT 交班记录查询（`BackService.sendShifts`）均待现场验证——编译通过和单测通过只证明结构迁移未破坏现有编译/测试，不代表行为已在真机上验证

## 4. 出入库领域（StockRepository）

- [x] 4.1 新建 `StockRepository` 接口与实现——`repository/StockRepository.java`（接口，覆盖 `OilInOut`/`OutStock`/`StockSave`/`DetailInoutRecord` 四个实体）+ `repository/StockRepositoryImpl.java`（单例实现）
- [x] 4.2 **与原计划不同**：`InOutAdapter`、`OutStockAdapter`、`StockSaveAdapter` 复核后均不调用本域任何 `DBDataManager` 方法（三者都只查询 `OilCanSet`，与 `DetailCommitWorkAdapter` 是同一类"名字像但域不对"的情况，未改动）。实际迁移的调用方是 9 个：`InOilFragment`（7 处：`delteRecord`/两处 `findLimit`/`findAllObject`/`findObjectByID`×2/`UpdateObject`×2）、`OutStockFragment`（4 处）、`StockFragment`（4 处）、`JiLuFragment`（`delteAllInOilRecord`×2，`OilInOut`/`OutStock`；`StockSave`那处也一并改）、`PrintRunable`（`findLast(OilInOut.class)`）、`InOilManager`（自动检测判定后的落库：`saveObject`/`findLast`×2 + `DetailInoutRecord`）、`BuLuFragment`（补录入库/出库的 `saveObject`/`findLast`）、`SaveOilManager`（`SaveOilDB()` 的 `StockSave` 写入；`SaveOilDB(String remark)` 那个重载写的是 `OilSaveInfo`，不属于本域，未改动）、`OilDischarge`（web handler，`findObject`→`findInPaged`）、`BackService`（MQTT `sendUnloadOils`）。**`SaveOilFragment` 确认是死代码**（`git grep -F` 全项目无任何实例化引用，标题也和 `StockFragment` 重复，均为"库存记录"），仍调用 `dbManager.findLimit(OilInOut.class,...)`，故意未migrate——它不在真实调用路径上，迁移它没有安全收益，只会掩盖这块死代码本该被单独评估删除的事实
- [x] 4.3 `DBDataManager` 中出入库相关方法标记 `@Deprecated`——只有 `saveSaveOilRecord(StockSave)` 是真正的出入库专属方法（且本就零调用方，随本次一并标记）；`findAllObject`/`findLimit`/`findLast`/`findObjectByID`/`UpdateObject`/`delteRecord`/`delteAllInOilRecord`/`findObject`/`findLimitRecord` 都是跨域共享的泛型方法，目前仍被 `AlarmHandler`（`findObject(Alarm.class,...)`）等未迁移域直接调用，**不能整体标记 `@Deprecated`**，留到 5/6 组把报警、油罐也迁完后再在收尾任务（第7组）里处理
- [x] 4.4 跑 `./gradlew :app:assembleDebug` 与 `:app:cleanTestDebugUnitTest :app:testDebugUnitTest`，确认通过——均 `BUILD SUCCESSFUL`；干净重跑读取 `app/build/test-results/testDebugUnitTest/*.xml`：81 个测试全部通过（0 failures / 0 errors），`ProbeReplayTest` 7 个 skipped 与本次改动无关
- [ ] 4.5 **真机手工回归（不可跳过，尚未进行）**：本机无 adb/真机可用。待验证：入库/出库记录的自动检测落库（`InOilManager`）、`InOilFragment` 人工卸油流程（`inout_save` 按钮的 `DetailInoutRecord`/`OilInOut` 更新）、出库/库存记录查询与删除、`BuLuFragment` 补录入库出库、库存快照保存（`SaveOilManager.SaveOilDB()`）、PC 端 `OilDischarge` 接口分页查询、MQTT `sendUnloadOils`——编译通过和单测通过只证明结构迁移未破坏现有编译/测试，不代表行为已在真机上验证

## 5. 报警领域（AlarmRepository）

- [x] 5.1 新建 `AlarmRepository` 接口与实现——`repository/AlarmRepository.java`（接口）+ `repository/AlarmRepositoryImpl.java`（单例实现）
- [x] 5.2 **与原计划不同**：`AlarmManager` 复核后不调用任何 `DBDataManager` 报警持久化方法——它只调用了 `sendOilMsg`（空实现的消息占位方法，不落库，未改动，见下）。实际迁移的调用方是 7 个：`AlarmFragment`（`findAllObject` 包装/导出/`delteRecord` 共3处）、`AlarmDetection`（7 处 `saveAlarm`，域内唯一大调用面）、`SerialPortManager`（协议编排层探头断线时的 1 处 `saveAlarm`）、`AsyManager2`（顶层 `asynmsg` 包，1 处 `setAramRecord`，`AsyManager` 里的同名调用是注释掉的死代码，未动）、`JiLuFragment`（`delteAllInOilRecord(Alarm.class)`）、`AlarmHandler`（web handler，`findObject`→`findPaged`）、`BackService`（MQTT `sendAlarms` 查询部分，不是 `harden-device-external-access` §3 要改的那个单条发布方法，两者互不冲突）
- [x] 5.3 `DBDataManager` 中报警相关方法标记 `@Deprecated`——`saveAlarm`/`setAramRecord`/`getLastAlarm`/`SaveDB` 全部标记；后两个（`getLastAlarm`/`SaveDB`）经 `git grep -F` 核实迁移前就已经零外部调用方（`SaveDB` 全项目找不到任何调用，`getLastAlarm` 只被同样零调用方的 `SaveDB` 内部引用），本次未移植到新 Repository，只做标记，做法与 3.3/4.3 对"死代码"的处理一致。原 `saveAlarm` 里调用的 `sendOilMsg(oilMessage)` 是空方法体（无任何副作用），移植时未复刻这一句，理由见 `AlarmRepositoryImpl.saveAlarm` 内的注释；`sendOilMsg` 本身留在 `DBDataManager` 上未动，因为 `AlarmManager` 仍在直接调用它
- [x] 5.4 跑 `./gradlew :app:assembleDebug` 与 `:app:cleanTestDebugUnitTest :app:testDebugUnitTest`，确认通过——均 `BUILD SUCCESSFUL`；干净重跑读取 `app/build/test-results/testDebugUnitTest/*.xml`：81 个测试全部通过（0 failures / 0 errors），`ProbeReplayTest` 7 个 skipped 与本次改动无关
- [ ] 5.5 **真机手工回归（不可跳过，尚未进行）**：本机无 adb/真机可用。待验证：触发一次真实报警场景确认记录/查询/报警声光联动均正常、探头断线告警（`SerialPortManager`）、报警记录删除与批量清空、Excel 导出、PC 端 `AlarmHandler` 接口分页查询、MQTT `sendAlarms` 查询——编译通过和单测通过只证明结构迁移未破坏现有编译/测试，不代表行为已在真机上验证
- [x] 5.6 与 `harden-device-external-access` §3（报警上报诚实性修正）协调：已核实该组仅 3.1 完成、3.2-3.8 均未开始且被该 change 自己标注为"不宜单独先做"（等 issue #4 MQTT 链路接通后才动），本次改动只涉及查询路径（`sendAlarms(int,...)`）和持久化调用点替换，未触碰 3.2-3.8 要改的单条发布方法 `sendAlarms(String)`/队列补投逻辑，两者不冲突

## 6. 油罐领域（OilTankRepository）—— 调用面最大，放最后

- [x] 6.1 新建 `OilTankRepository` 接口与实现——`repository/OilTankRepository.java`（接口，覆盖 `OilCanSet`/`OilCanTable`/`ProbeToOil`/`Sideleakage`/`OilCanMapper`/`OilExploration` 六个实体）+ `repository/OilTankRepositoryImpl.java`（单例实现）
- [x] 6.2 **与原计划不同（本域偏差最大）**：`SaveOilManager`/`LoginUser`/`ExportExcel`/`SideleakageDetection` 复核后均不需要改——`SaveOilManager` 只写 `StockSave`/`OilSaveInfo`（已在 §4 处理/不属本域）；`LoginUser` 只调 `findAuthorization`（`Authorization` 实体，登录鉴权，不是油罐数据）；`ExportExcel` 只是把 Excel 文件解析成 `OilCanSet`/`OilCanTable`/`ProbeToOil` 对象，从不直接碰 `DBDataManager`，真正持久化这些对象的是调用方 `CreateOilTableFragment`；`SideleakageDetection` 持有一个 `dbDataManager` 字段但从未实际调用过，是死字段。实际迁移的调用方有 12 个，比任务描述里点名的更多也更杂：`MainActivity`（`OilCanMapper`）、`OilcanDataManager`（核心数据装载路径 `setProbeData`：`OilCanSet`×2/`OilCanTable`×1，另有 `OilExploration`×2；**注意**：`setTestOilCan`/`setTestOilcanTable` 两个私有方法里各有一处 `OilCanMapper.findObjectByID` 调用，经 `git grep -F` 核实这两个方法在类内部零调用，是不可达死代码，未迁移）、`CreateOilTableFragment`（`SaveOilCanTable`→`exportOilCanTable`、`UpdateObject`、`findAllObject(OilCanTable.class)`）、`BuLuFragment`（上次 §4 迁移时遗漏的部分，本次一并确认无遗留）、`PrintRunable`（`Sideleakage` `findLast`）、`OilCanSetFragment`（油罐设置页，6 处：`findAllObject`/4×`UpdateObject`/`findObjectByID`，本域调用面最大的单个文件）、`AlarmOpenAdapter`（`OilCanSet` 的 `findObjectByID`+`UpdateObject` 各 2 处）、`StockSaveAdapter`/`InOutAdapter`/`OutStockAdapter`/`DetailCommitWorkAdapter`（各 1 处 `OilCanSet.findAllObject`，用于取密度）、`OutStockFragment`/`InOilFragment`/`StockFragment`（各 1 处 `OilCanMapper.findAllObject`）、`BaseActivity`（`ProbeToOil`，位置上报用）、`BackService`（MQTT `sendLeakOils` 查询）。**`SaveOilFragment` 里同样的 `OilCanMapper` 读取调用故意未迁移**——该文件在 §4 已确认是死代码（全项目无实例化），此次复核结论不变
- [x] 6.3 `DBDataManager` 中油罐相关方法标记 `@Deprecated`——`setOilTableToOilCan`/`findAllProbeToOil`/`saveSideleakageRecord`/`findTemper`/`SaveOilCanTable`/`OilCanTableCopy` 六个专属方法全部标记；前五个经 `git grep -F` 核实迁移前就已零外部调用方（`TempRecord` 实体全项目除 `findTemper` 自身外没有任何其它引用，是完全死掉的实体访问路径），`SaveOilCanTable` 迁移后归零。均未移植死方法本体到新 Repository，只做标记，与 3.3/4.3/5.3 处理方式一致
- [x] 6.4 跑 `./gradlew :app:assembleDebug` 与 `:app:cleanTestDebugUnitTest :app:testDebugUnitTest`，确认通过——均 `BUILD SUCCESSFUL`（`assembleDebug` 首次编译即过，未触发任何因遗漏调用点导致的编译错误）；干净重跑读取 `app/build/test-results/testDebugUnitTest/*.xml`：81 个测试全部通过（0 failures / 0 errors），`ProbeReplayTest` 7 个 skipped 与本次改动无关
- [ ] 6.5 **真机手工回归（不可跳过，尚未进行）**：本机无 adb/真机可用。待验证：新建/编辑油罐配置（`OilCanSetFragment`）、油罐罐表标定导入导出（`CreateOilTableFragment`）、报警开关批量设置（`AlarmOpenAdapter`）、出入库/交班/库存/报警四个记录列表页正常显示密度相关字段（4 个 Adapter 的 `OilCanSet` 读取）、测试数据映射（`OilCanMapper`，`MainActivity.updateTestOil`）、探针地址上报（`BaseActivity`）、侧漏记录打印与 MQTT 查询——编译通过和单测通过只证明结构迁移未破坏现有编译/测试，不代表行为已在真机上验证，尤其是 `OilcanDataManager.setProbeData` 这条核心数据装载路径必须重点回归

## 7. 收尾：删除 DBDataManager

- [x] 7.1 **确认结果：做不到"全项目已无非 `@Deprecated` 调用"——`DBDataManager` 上仍有大量在用、且从未被本变更纳入范围的方法。** 用 `git grep -F "DBDataManager"` + 对每个命中文件逐一检查（非 `\|`/`\b` 正则，遵循 `CLAUDE.md` 的环境限制）核对了 28 个原始调用方文件，结论分两类：
  - **本次迁移过程中确实发现一处遗漏并已现场修复**：`fragment/ColorFragment.java:99`（罐颜色选择的 `onItemClick`，真实可达的 UI 交互路径，不是死代码）在调 `dbManager.UpdateObject(oilCanSet)`，属于 §6 `OilTankRepository` 范围内、当时排查调用方时漏掉的一个文件（§6 清单未列出它）。已改为 `oilTankRepository.updateOilCanSet(oilCanSet)`，验证见 7.1 末尾。
  - **其余非 `@Deprecated` 调用点是三个从未被本变更列入范围的领域，加上一组明确要跨领域共享、不应领域化的通用方法**——proposal.md「Why」只统计过"28 个文件调用 DBDataManager"，但没有審查这些方法归属哪些领域；design.md 也只承诺"油罐/出入库/报警/交班"四个领域收敛：
    - **User/Authorization 域**（`isUserExist`/`chekIsExists`/`checkObjectNotNull`/`findAuthorization`/`removeObject`/`findUser`/`findAdminUser`）：`logic/LoginUser.java`（登录/注册整个流程）、`fragment/SetBaseFragment.java:110`（`findUser`）
    - **OilTest 域**（`findTestObject`/`findAllObject(OilTest.class)`/`findObjectByID`/`UpdateObject`/`saveObject`）：`fragment/OilTestFragment.java`（完整 CRUD）、`activity/MainActivity.java:282`
    - **OilSaveInfo 域**（`findLast`/`findLimitRecord`/`saveSaveOilRecord(String,String)` 重载）：`logic/PrintRunable.java`（打印场景 4）、`service/BackService.java:744`（MQTT 查询）、`manager/SaveOilManager.java`（库存快照写入）——注意这不是 §4 标记 `@Deprecated` 的 `saveSaveOilRecord(StockSave)` 重载，是另一个签名，§4.3 已明确记录"不属本域，未改动"
    - **跨领域通用方法**（Gson 序列化 `stringToArray`/`getListToStringByGson`/`getStringToListByGson`/`parseObjectWithGson`，以及泛型 CRUD `saveObject`/`findAllObject(Class)`/`findObjectByID`/`findLast`/`UpdateObject`/`delteRecord`/`delteAllInOilRecord`/`findLimitRecord`/`findLimit`/`findObject`/`Sort`/`DeleteTable`/`findFirst`/`ResolveInt`/`findBetweenTimeOilInOut`/`findInOilByPos`）：这些方法本身不属于任何单一领域，被 `ExportExcel`/`UpdateCommitDialog`/`CommitWorkRecortFragment`（继承字段 `dbManager`，非局部变量）/`DetailCommitWorkFragment` 等文件继续调用，且 §3/§4 的任务描述已经明确把它们排除在迁移范围外（"通用 Gson 帮助方法，不属于本次迁移范围"）
  - **顺带清理**：`fragment/CommitWorkRecortFragment.java` 与 `fragment/CreateOilTableFragment.java` 里各有一个此前标注"出于范围外未清理"的死局部变量 `DBDataManager db = MyApplication.getInstance().getmDBManager();`（只被注释掉的代码引用），本次一并删除，连带清掉两处不再需要的 `import`
  - `manager/OilcanDataManager.java` 的字段 `db`（`DBDataManager` 类型）只被两个类内部零调用的死方法 `setTestOilCan`/`setTestOilcanTable` 引用（§6 已记录为不可达死代码），维持不动
  - `manager/AlarmManager.java:98` 调 `DBDataManager.getInstance().sendOilMsg(...)`——空实现方法，§5.2 已记录原因，维持不动
  - 验证：`./gradlew :app:assembleDebug`（`BUILD SUCCESSFUL`）+ `./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest`（干净重跑，读取 XML：81 个测试、0 failures/0 errors，`ProbeReplayTest` 7 个 skipped 与本次改动无关，计数与迁移前一致）
- [x] 7.2 **用户已决策：方向 1——就地保留 `DBDataManager`，不改名。** 字面意义的"删除 DBDataManager 类"确认做不到（见 7.1）；改名（方向 3）因"~15 处类型声明全改、结构零变化、无实际收益"被否决，理由与 `design.md` 里"不合并/重命名两个 `SerialPortManager`"一致；开新 OpenSpec 变更覆盖 User/Auth+OilTest+OilSaveInfo（方向 2）留作未来可选项，不在本次执行。`DBDataManager` 的定位正式改写为"User/Auth + OilTest + OilSaveInfo 三个未领域化业务 + 跨领域通用 CRUD/Gson 帮助方法的常驻类"，不再是待删除的过渡类。
- [x] 7.3 更新 `CLAUDE.md`：`Data flow` 一节新增 `repository/` 条目（六个实体、四个域、strangler-fig 说明），`manager/` 条目更新 `DBDataManager` 定位，`db/` 条目补充"六个实体走 Repository，其余仍走 DBDataManager"，并新增 `### DBDataManager: permanently partial, not slated for deletion` 小节完整记录 7.2 的决策依据（三个未覆盖域 + 通用方法清单 + 为什么不改名）

## 8. 硬件边界：SerialPortManager 去 UI 依赖（放最后，需真机验证时序）

- [x] 8.1 新建 `manager/DataRefreshCallback.java`，单方法接口 `void onDataRefresh(OilCan oilCan)`
- [x] 8.2 `com.sansen.yeweiyi.manager.SerialPortManager`：删掉 `import com.sansen.yeweiyi.base.BaseActivity`；`private Handler mHandler` → `private DataRefreshCallback mDataRefreshCallback`；`setHandler(Handler)` → `setDataRefreshCallback(DataRefreshCallback)`；`hexDataParseFinish`/`hexTestDataParseFinish` 两处原先手工 `Message.obtain()` + `msg.what = BaseActivity.DATA_REFRESH` + `mHandler.sendMessage(msg)` 都改成直接 `mDataRefreshCallback.onDataRefresh(oilCan)`。`android.os.Handler`/`Message` 的 import 保留——同文件另有一个内部 `handler` 字段（探头断线报警用）与本次改动无关，未动
- [x] 8.3 `BaseActivity` 新增 `private final DataRefreshCallback dataRefreshCallback` 字段，`onDataRefresh` 内部原样构造 `Message`（`what=DATA_REFRESH`、`obj=oilCan`）再 `handler.sendMessage(msg)`——刻意保留原有内部 `Handler`/`handleMessage` switch 完全不变，只是把"谁来构造并投递这条 Message"从 `SerialPortManager` 手上搬回 `BaseActivity` 自己手上，确保"探头线程 → 主线程"这一跳的行为字节级不变。`onResume()` 里 `mSerialPortManager.setHandler(handler)` 改成 `mSerialPortManager.setDataRefreshCallback(dataRefreshCallback)`
- [x] 8.4 确认第 1 组的两个录制钩子未受影响：`ProbeTrafficRecorder.recordRequest`（`RequestThread.run()` 里，本次未触碰的输入接缝）与 `ProbeTrafficRecorder.recordParsed`（`hexDataParseFinish` 内，位于本次改动的回调调用之前，行为不变）
- [x] 8.5 两个 `SerialPortManager` 类头都加了类注释——`manager/SerialPortManager.java` 标注"协议编排层，不是 HAL"，`serialport/manager/SerialPortManager.java` 标注"真正的 HAL，UI/业务层禁止直接调用"；`CLAUDE.md` "Known landmines" 新增一条记录这对同名类的区别与本次改动
- [x] 8.6 跑 `./gradlew :app:assembleDebug`（`BUILD SUCCESSFUL`）与 `:app:cleanTestDebugUnitTest :app:testDebugUnitTest`（干净重跑，读取 XML：81 个测试、0 failures/0 errors，与迁移前基线一致）
- [ ] 8.7 **真机验证（不可跳过，尚未进行）**：本机无 adb/真机可用。待验证：探头数据到达后 UI 刷新时机与改动前一致、无延迟或丢失——结构上这次改动是把"构造+投递 Message"的责任从 `SerialPortManager` 挪回 `BaseActivity`，逻辑等价，但 `design.md` Risks 明确要求这类接缝改动必须真机确认，不能只凭编译/单测通过判定完成
