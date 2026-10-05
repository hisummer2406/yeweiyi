# 协议 V1 共享 fixture（来自 yeweiyi-cloud）

本目录下的 `*.json` 是 `yeweiyi-cloud/fixtures/protocol/v1/` 的**原样副本**，不要在这里改内容。

- 来源：`yeweiyi-cloud@26fb6b6`（协议代码与 `5ff243b` 相同）
- 拷贝日期：2026-10-04
- 使用方：`app/src/test/java/com/sansen/yeweiyi/logic/thingsboard/ProtocolV1ContractTest.java`、`StockEventMapperTest.java`

## 同步方法

云端协议有改动时，重新整目录拷贝，然后跑测试：

```bash
cp ../yeweiyi-cloud/fixtures/protocol/v1/*.json app/src/test/resources/protocol/v1/
./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest
```

测试变红说明两边协议出现漂移，先跟云端确认再改代码，不要改 fixture 迁就代码。拷贝后更新上面的来源 commit。

## 各 fixture 在 Android 侧的覆盖情况

| fixture | Android 方向 | 覆盖 |
|---|---|---|
| `telemetry_snapshot.json` | 编码 | ✅（`waterRatioPct` 暂不上报，为已知缺口） |
| `alarm_active.json` / `alarm_cleared.json` | 编码 | ✅ |
| `stock_event_in.json` / `stock_event_out.json` | 编码 | ✅ |
| `client_attributes.json` | 编码 | ✅（`deviceModel`/`hardwareVersion`/`localControlMode`/`current_fw_*` 无数据来源不发；`probeType` 取值待约定——均为已知缺口） |
| `rpc_request.json` | 解码 | ✅ |
| `rpc_ack_received/rejected/duplicate.json` | 编码 | ✅ |
| `cmd_result_succeeded/failed.json` | 编码 | ✅ |
| `telemetry_snapshot_forward_compat.json` / `telemetry_snapshot_rest.json` | 解码遥测 | 不适用：Android 只发不收遥测，没有遥测解析器 |

"已知缺口"由测试固定当前行为：缺口补上后对应的 `knownGap_*` 测试会变红，届时删掉它，并把该字段加回逐字段比对。
