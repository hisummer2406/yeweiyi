# protocol-parser-testability Specification

## Purpose

提供探头链路现场报文的录制能力，使真实工况下的输入帧与解析产出可被带回开发环境重放，作为日后改动解析逻辑时的行为基线素材。

## Requirements

### Requirement: 具备现场报文录制能力
设备 SHALL 提供一个默认关闭、可经构建期配置开启的录制能力，把探头链路的原始输入字节帧与其对应的解析产出成对落盘，使真实工况下的报文可被带回开发环境重放。

录制点为探头轮询链路的一进一出两处接缝：输入侧是 `RequestThread.run()` 中串口读出并转成 hex 字符串之后、分发给 `probeInfoPase` 之前的那一点；输出侧是 `IParseDataListener.hexDataParseFinish(OilCan)`。

录制的输入形态 SHALL 是喂给 `probeInfoPase(String, IParseDataListener)` 的 hex 字符串本身，而非其上游的原始 `byte[]`——该字符串是解析逻辑真正的输入，也是后续抽取出的纯解析类的输入，以此保证重放喂进去的与现场当时喂进去的是同一个东西。

**范围**：本能力覆盖探头轮询路径上的三种 `probeType`（`0` 中诚 / `1` LD / `2` LD2）。`VR350Factory` 不在此路径上——它是 `PCDataFactory`，服务于 PC 通信线程，不经由上述分发 switch，也不回调 `hexDataParseFinish`，其录制与测试另行处理。

#### Scenario: 开启录制后采集一段真实工况
- **WHEN** 在真实设备上开启录制标志并连接真实探头运行
- **THEN** 每一帧到达的原始字节（含时间戳与当时的探头类型）与其解析产出的 `OilCan` 字段快照被成对写入存储，且配对关系可无歧义还原

#### Scenario: 录制关闭时零影响
- **WHEN** 录制标志为默认的关闭状态
- **THEN** 探头数据的接收与解析路径行为与未引入录制能力时完全一致，不产生额外落盘或可观察的时序变化
