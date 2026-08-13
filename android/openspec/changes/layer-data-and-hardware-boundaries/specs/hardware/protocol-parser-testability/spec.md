## Purpose

让 `factory/` 各厂商协议解析中不依赖 Android 框架的部分具备纯 JVM 单元测试能力，不需要 Robolectric 或真实设备。

## ADDED Requirements

### Requirement: 具备现场报文录制能力
设备 SHALL 提供一个默认关闭、可经构建期配置开启的录制能力，把探头链路的原始输入字节帧与其对应的解析产出成对落盘，使真实工况下的报文可被带回开发环境重放。

录制点为协议链路的一进一出两处接缝：输入侧 `ProbeReciveDataListener.reciveData(byte[])`，输出侧 `IParseDataListener.hexDataParseFinish(OilCan)`。

#### Scenario: 开启录制后采集一段真实工况
- **WHEN** 在真实设备上开启录制标志并连接真实探头运行
- **THEN** 每一帧到达的原始字节（含时间戳与当时的探头类型）与其解析产出的 `OilCan` 字段快照被成对写入存储，且配对关系可无歧义还原

#### Scenario: 录制关闭时零影响
- **WHEN** 录制标志为默认的关闭状态
- **THEN** 探头数据的接收与解析路径行为与未引入录制能力时完全一致，不产生额外落盘或可观察的时序变化

### Requirement: 抽取前的行为基线可重放
在对解析逻辑做任何结构性改动之前，SHALL 存在一组以现场采集报文为输入、以采集当时的解析结果为断言的重放测试，用于锁定改动前的既有行为。

#### Scenario: 基线测试在未改动代码上通过
- **WHEN** 用现场采集的 fixture 对**尚未做任何抽取改动**的解析路径跑重放测试
- **THEN** 全部用例通过——该测试描述的是代码当前的实际行为，而非它理应具备的行为

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
