# 协议 V1 共享 fixture 的 Android 覆盖情况

Android 单元测试直接读本目录（`android/app/build.gradle` 把 `cloud/fixtures` 挂到测试 classpath，
按 `/protocol/v1/<name>` 读取），**不再有副本**，也就不需要同步。改这里的 fixture 后两端测试都要跑：

```bash
(cd cloud && go test ./internal/protocol/)
(cd android && ./gradlew :app:testDebugUnitTest)
```

测试变红说明两边协议出现漂移，先跟另一端负责人确认再改代码，不要改 fixture 迁就代码。
使用方：`android/app/src/test/java/com/sansen/yeweiyi/logic/thingsboard/` 下的
`ProtocolV1ContractTest`、`StockEventMapperTest` 等。

## 设备端起草、待云端收录的 fixture（zhongchengyeweiyi#49 / cloud#85）

下列文件由设备端手写（不是实现序列化出来的），云端拷进 `yeweiyi-cloud/fixtures/protocol/v1/` 并登记 MANIFEST 后，
以云端副本为准，按下面的同步方法回拷：

| fixture | 内容 | Android 覆盖 |
|---|---|---|
| `shift_event_open.json` / `shift_event_closed.json` | 交班开始/结束 | `ShiftEventMapperTest` |
| `shift_event_tank_added.json` / `shift_event_tank_removed.json` | 班内增/删罐 | `ShiftEventMapperTest` |
| `stock_record.json` | 一次库存保存（多罐） | `StockRecordMapperTest` |
| `stock_event_in_delivered_weight.json` | stockEvent 新增 `oilTypeIndex`、`deliveredWeightKg` | `StockEventMapperTest` |
| `alarm_active_oil_type.json` | alarm 新增 `oilType`、`oilTypeIndex` | `ProtocolV1ContractTest` |
| `oil_type_index.json` | `oilTypeIndex` 对照表（字典，不是报文） | `OilTypeIndexFixtureTest`（与 `res/values*/array.xml` 比对） |


## 各 fixture 在 Android 侧的覆盖情况

| fixture | Android 方向 | 覆盖 |
|---|---|---|
| `telemetry_snapshot.json` | 编码 | ✅（`waterRatioPct` 暂不上报，为已知缺口） |
| `alarm_active.json` / `alarm_cleared.json` | 编码 | ✅ |
| `stock_event_in.json` / `stock_event_out.json` | 编码 | ✅ |
| `client_attributes.json` | 编码 | ✅ 含 #14 标定表 `tank1.calibration*`（`TankCalibrationAttributeBuilder`）；（`deviceModel`/`hardwareVersion`/`localControlMode`/`current_fw_*` 无数据来源不发；`probeType` 取值待约定——均为已知缺口） |
| `rpc_request.json` | 解码 | ✅ |
| `rpc_ack_received/rejected/duplicate.json` | 编码 | ✅ |
| `cmd_result_succeeded/failed.json` | 编码 | ✅ |
| `telemetry_snapshot_forward_compat.json` / `telemetry_snapshot_rest.json` | 解码遥测 | 不适用：Android 只发不收遥测，没有遥测解析器 |

"已知缺口"由测试固定当前行为：缺口补上后对应的 `knownGap_*` 测试会变红，届时删掉它，并把该字段加回逐字段比对。
