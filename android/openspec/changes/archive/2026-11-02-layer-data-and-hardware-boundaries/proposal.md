## Why

`DBDataManager`（1027 行，60 个 `public` 方法）是全项目数据访问的唯一入口，被 28 个文件直接调用（8 个 Fragment/Adapter、多个 Manager、两个 web handler、`MainActivity`）。任何新业务需求都在继续往这个类里堆方法，而没有领域边界。同时，串口/协议层存在一个被误判为"已经很干净"的耦合：`com.sansen.yeweiyi.manager.SerialPortManager`（498 行，被 `MyApplication`/`BaseActivity`/`TCPClient`/`PrintRunable`/`VR350Factory` 依赖）直接 `import` 了 `BaseActivity`、硬编码持有全部 4 家厂商 `factory/` 解析器，且携带一处已知未修的协议分发缺陷（`case 1` 缺 `break`，见 `docs/重构方案.md` 4.5.2）。`docs/重构方案.md` 阶段2 原描述"SerialPortManager 已是事实上的 HAL，零 UI 依赖，只差正式确立"——这个判断把它和另一个同名但几乎没人用的类（`serialport.manager.SerialPortManager`，263 行，真正零 UI 依赖）搞混了。真实情况是硬件边界并不像文档说的那样干净，需要先厘清两者关系再谈"确立"。

## What Changes

- 拆 `DBDataManager` 为 4 个按领域划分的 Repository：`OilTankRepository`（油罐）、`StockRepository`（出入库，服务 `InOilFragment`/`OutStockFragment`/`StockFragment`）、`AlarmRepository`（报警）、`ShiftRepository`（交班，服务 `CommitWorkRecortFragment`）。UI 层与业务 Manager 只依赖 Repository 接口，不再直接调用 `DBDataManager`。
- 逐个方法搬移，每搬一批跑一次 `assembleDebug` + 现有 38 个单测，确认不破坏编译；不做"一次性删除 DBDataManager"式的大爆炸重写（呼应硬约束1）。
- 澄清并记录两个 `SerialPortManager` 的实际职责边界：`com.sansen.yeweiyi.manager.SerialPortManager`（协议编排 + UI 回调）与 `serialport.manager.SerialPortManager`（底层 JNI 字节收发）。正式确立后者为 HAL，前者降级为"编排层"，并去掉编排层对 `BaseActivity` 的直接引用（改为回调/接口，不在 Impact 之外扩大改动）。
- 抽取 `factory/`（`LDCompany`/`LD2Company`/`ZhongChengCompany`/`VR350Factory`）中不依赖 `android.*` 的纯解析逻辑到独立类，使其可脱离 Robolectric 直接跑 JVM 单测——这项工作在阶段1被明确推迟到阶段2（`docs/重构方案.md` "对原方案的两处修正" 第1条）。

## Capabilities

### New Capabilities
- `data/oil-tank-repository`：油罐数据的读写边界
- `data/stock-repository`：出入库数据的读写边界
- `data/alarm-repository`：报警数据的读写边界
- `data/shift-repository`：交班数据的读写边界
- `hardware/serial-port-hal`：串口硬件访问边界规则（UI/业务层不得绕过编排层直接触达 JNI 层；两个同名类的职责被明确区分）
- `hardware/protocol-parser-testability`：`factory/` 厂商协议解析脱离 `android.*` 依赖，具备纯 JVM 单测能力

### Modified Capabilities
（无——现有代码没有被记录为 OpenSpec 能力，此变更是本项目首次引入这些能力定义）

## Impact

- **受影响代码**：`manager/DBDataManager.java`（拆分）；28 个当前直接调用 `DBDataManager` 的文件（改为依赖对应 Repository，逐个迁移，见 tasks.md）；`manager/SerialPortManager.java`（498行，去 UI 依赖）；`serialport/manager/SerialPortManager.java`（正式确立为 HAL，本次不改动其内部实现）；`factory/LDCompany.java`、`factory/LD2Company.java`、`factory/ZhongChengCompany.java`、`factory/VR350Factory.java`（抽取纯解析逻辑）。
- **触及硬件/协议层，需要真机验证**：本次改动涉及 `factory/` 与 `manager/SerialPortManager.java`（协议分发编排），任何对 `factory/` 解析逻辑的抽取、对 `SerialPortManager` 调用路径的改动，在本机（无真实探头硬件）只能验证"编译通过 + 单测通过"，**无法验证真实探头读数是否正确**，必须上真机回归后才能视为完成。
- **不在本次范围**：`case 1` 缺 `break` 的协议分发 bug（`docs/重构方案.md` 4.5.2）本身不在本变更中修复——那是行为变更，需产品/硬件侧先确认现场 LD 探头的真实读数情况，不应该和"确立 HAL 边界"这类结构性改动混在一起提交。
- **对三条硬约束的态度**：respects 约束1（按领域逐个 Repository 迁移，不整体重写）；respects 约束2（`factory/` 抽取的第一步就是让它可测，测试先于任何后续行为改动）；respects 约束3（硬件协议层的耦合梳理优先于任何 UI 相关工作，这也是把阶段2排在阶段3之前的原因）。
