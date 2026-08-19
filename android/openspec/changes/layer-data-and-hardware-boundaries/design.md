## Context

`DBDataManager` wraps `DaoHelp`（xUtils `DbUtils`）并暴露 60 个 `public` 方法，是全项目持久化的唯一入口（`docs/重构方案.md` 记录的"数据库访问已收敛到 3 个文件"指的是 `DbUtils`/`DaoHelp` 的直接引用，不是 `DBDataManager` 的调用方——`DBDataManager` 本身被 28 个文件调用）。同时项目里有两个同名 `SerialPortManager`：`serialport.manager.SerialPortManager`（263行，JNI 字节收发，零 UI 依赖，几乎没有调用方）和 `com.sansen.yeweiyi.manager.SerialPortManager`（498行，协议编排，被 5 个文件依赖，`import` 了 `BaseActivity`）。详见 proposal.md - Why。

## Goals / Non-Goals

**Goals:**
- 让每个领域（油罐/出入库/报警/交班）的数据访问收敛到各自的 Repository 接口，新业务代码不再往 `DBDataManager` 里加方法。
- 让协议编排层（`com.sansen.yeweiyi.manager.SerialPortManager`）不再直接 `import` UI 类，改用回调接口传递刷新信号。
- 让 `factory/` 的纯字节解析逻辑可以脱离 `android.*` 被 JVM 单测覆盖。

**Non-Goals:**
- 不引入依赖注入框架（Dagger/Hilt）或 Repository 之上的抽象层——按硬约束1，只做接口拆分，不做架构升级。
- 不合并/重命名两个 `SerialPortManager` 类——重命名会扩大改动面且没有实际收益，本变更只是分别标注职责、不做结构合并。
- 不修复 `case 1` 缺 `break` 的协议分发 bug——那是行为变更，需要产品/硬件侧先确认，不与结构性重构混在同一批提交里（见 proposal.md 明确排除）。
- 不改变底层存储机制（仍是 xUtils `DbUtils`，不换 Room/GreenDAO）。

## Decisions

**1. Repository 用"绞杀者模式"（Strangler Fig）落地，不做一次性切换。**
新建 4 个 Repository 类，内部直接调用现有的 `DaoHelp`/`DbUtils`（与 `DBDataManager` 当前调用的是同一套存储）。之后逐个把调用方从 `DBDataManager.xxx()` 改成 `对应Repository.xxx()`，`DBDataManager` 对应方法标记 `@Deprecated` 但不立即删除。等一个领域的所有调用方都迁移完，才在收尾任务里删掉 `DBDataManager` 里那部分方法。
*备选方案*：让 `DBDataManager` 内部委托给四个 Repository（反向绞杀）。放弃原因：这样 `DBDataManager` 会继续作为事实上的单一入口存在，达不到"新代码不再找它"的目的。

**2. `SerialPortManager` 编排层去 UI 依赖，用回调接口替代 `BaseActivity` 引用。**
现状 `msg.what = BaseActivity.DATA_REFRESH` 是编排层直接引用 UI 层常量。改为定义一个 `interface DataRefreshCallback`，由 `BaseActivity` 实现并注册，编排层只依赖这个接口。`serialport.manager.SerialPortManager`（真正的 HAL）本次不改动内部实现，只在类注释里正式标注"这是硬件访问边界，禁止 UI/业务层直接调用，一律经 `com.sansen.yeweiyi.manager.SerialPortManager` 编排层"。
*备选方案*：把两个类合并成一个。放弃原因：编排层携带已知未修的 FIXME bug，合并会把"结构重构"和"修复未决行为缺陷"耦合在一次改动里，违反硬约束1的风险控制原则。

**3. `factory/` 抽取采用"解析器拆两半"：`XxxCompany`（保留，处理 Android 相关部分，如有）+ `XxxParser`（新增，纯字节输入输出，无 `android.*` import）。**
`XxxCompany` 内部调用 `XxxParser`，行为不变，只是把可测的部分物理搬到新类。现场抓包的真实报文作为 fixture 字节数组喂给 `XxxParser`，断言解析出的液位/温度值，延续阶段1 `CRCheckTest` 的做法。
*备选方案*：引入 Robolectric 直接测原类。放弃原因：`docs/重构方案.md` 阶段1 已经论证过——引入 Robolectric 的收益不如抽出纯逻辑层，且抽出纯逻辑层是阶段2 本来就要做的结构性工作，一次投入两次受益。

## Risks / Trade-offs

- [风险] 拆分 `DBDataManager` 时遗漏跨领域的隐式依赖（例如报警逻辑读了油罐表的字段）→ 缓解：每次只迁移一个方法/一小批方法，迁移前先 grep 该方法体内触达的表名，迁移后跑 `assembleDebug` + 38 个现有单测。
- [风险] 编排层去掉 `BaseActivity` 直接引用后，回调时机/线程可能与原 `Handler`/`Message` 机制不完全一致，现场刷新可能出现细微时序差异 → 缓解：这部分**必须真机验证**，不能只凭编译通过判定完成（已在 proposal.md Impact 里注明）。
- [风险] `factory/` 拆出的 `XxxParser` 在边界值（负数温度、CRC 补零等，见 `CLAUDE.md` "Known landmines"）上引入细微行为差异 → 缓解：拆分前后用同一组现场报文跑一遍输出 diff，不允许有任何字节级差异，而不只是"编译通过"。

## Migration Plan

> **2026-08-13 按 `docs/重构方案.md` v2 重排。** 原顺序把 `factory/` 抽取放在全部 Repository 迁移之后，并称三者「可并行、互不阻塞」。
> 现修正为：`factory/` 抽取**优先于** Repository 迁移，且其自身以 golden master 录制为前置。理由见下第 1、2 条。

1. **先建立可验证性，再动任何解析代码。** 本机无法验证硬件行为，`assembleDebug` 绿只证明能编译。因此第一步是在协议链路的一进一出两处接缝加录制钩子、去现场采集真实报文、建立重放基线（characterization test），把「不可验证」变成「可验证」。这一步是全变更的单点依赖——它依赖现场档期，应最早排期。原计划的 §7.2 直接假设「现场抓取的真实报文」已存在，却没有任何任务产出它，这是原方案的一处缺口。
2. **`factory/` 抽取提到 Repository 之前，而不是并行或殿后。** 它同时卡住「补测试」与「改结构」两件事：最值得测的协议解析有 6/9 文件 `import android.*`，测它必须先改结构，改结构又缺测试保护。先解开这个死锁，同时它是纯函数、风险低、可用重放测试逐字节验证，适合作为验证迁移模式本身的第一批，并产出一份真实耗时数据用于校准后续估算。
3. 再按领域顺序落地 Repository：交班 → 出入库 → 报警 → 油罐。顺序按关键词粗略估算的调用方文件数从少到多排（交班约10、出入库约16、报警约19、油罐约24，关键词匹配存在重叠，仅作排序参考不是精确值），目的是让第一批迁移的风险最小，再应用到调用面更大的领域。
4. 每个领域内部：新建 Repository → 迁移调用方 → 标记 `DBDataManager` 对应方法 `@Deprecated` → （待全部领域完成后）统一收尾删除。
5. 硬件边界的回调重构放在最后：它需要真机验证刷新时序、无法用重放测试覆盖，且它改动的接缝正是第 1 条录制钩子所挂的位置，放在后面可避免两次调整。
6. 回滚策略：全程是纯代码结构改动，不涉及数据库 schema 变更，出问题直接 `git revert` 对应提交即可，无需数据迁移回滚。
