## Purpose

让 `factory/` 各厂商协议解析中不依赖 Android 框架的部分具备纯 JVM 单元测试能力，不需要 Robolectric 或真实设备。

## ADDED Requirements

### Requirement: 纯字节解析逻辑不依赖 android.*
每个厂商解析器（`LDCompany`/`LD2Company`/`ZhongChengCompany`/`VR350Factory`）的字节级解析逻辑 SHALL 位于不 `import` 任何 `android.*` 包的类中，使其可在纯 JVM 环境下被实例化和调用。

#### Scenario: 在无 Android 运行时环境下解析一帧真实报文
- **WHEN** 在 JVM 单元测试中，用现场抓取的真实字节报文调用某厂商解析器的纯逻辑部分
- **THEN** 解析出的液位/温度值与该报文对应的已知正确值一致，且整个调用过程不触发 `android.content.Context` 或 `SparseArray` 等 Android 类的加载

### Requirement: 抽取前后行为等价
抽取出的纯解析类 SHALL 对同一组现场报文产生与抽取前完全一致的解析结果。

#### Scenario: 回归对比无差异
- **WHEN** 用同一批现场报文分别跑抽取前的原始解析路径与抽取后的纯解析类
- **THEN** 两者输出的液位/温度等字段逐字节/逐值比对无任何差异
