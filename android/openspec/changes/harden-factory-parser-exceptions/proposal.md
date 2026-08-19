## Why

None of the four `factory/` vendor protocol parsers (`ZhongChengCompany`/`LDCompany`/`LD2Company`/`VR350Factory`) contain a single `catch (Exception e)` internally — a malformed frame from live, flaky RS-485/serial hardware propagates as an uncaught exception. Three of the four call sites already guard against this at the caller (`SerialPortManager`'s `case 0`/`case 1` wrap `probeInfoPase` in try/catch with logging), but two gaps remain, found during a repo-wide code quality pass:

- `SerialPortManager`'s `case 2` (`LD2Company`) calls `probeInfoPase` completely unguarded — an uncaught exception here propagates out of the polling loop. `LD2Company.probeInfoPase` is already documented (2026-08-15 comment, same file) as doing unchecked CRC-free parsing and array indexing by subscript, exactly the shape of input that throws `ArrayIndexOutOfBoundsException` on a malformed frame.
- `VR350Factory` is never dispatched through that switch at all — it runs on its own dedicated `HandlerThread("VR350Factory")`, and `handleMessage` calls `initData(String)` unguarded. An uncaught exception on a `HandlerThread` kills that thread's `Looper` silently; every future message posted to it (i.e. every future PC-protocol exchange) is then dropped with no further error until the app restarts.

## What Changes

- `SerialPortManager`: wrap the `case 2` (`LD2Company`) `probeInfoPase` call in the same try/catch + logging pattern already used by `case 0`/`case 1`, including `油罐位=` and raw-frame context in the log message.
- `VR350Factory`: wrap the `initData(String)` call inside `handler`'s `handleMessage` in a try/catch + logging, so a malformed PC-protocol frame is logged and dropped instead of killing the dedicated `HandlerThread`.
- No change to any parser's internal parsing logic, return values, or public method signatures — this only adds a catch boundary at each of the (now) four call sites, matching the pattern already established for `case 0`/`case 1`.

## Capabilities

### New Capabilities
- `probe-parser-fault-isolation`: a malformed or unparseable frame from any of the four vendor probe protocols is logged with enough context to diagnose (probe/oil-can position or raw frame) and discarded, without crashing or silently disabling the thread that processes further frames from that source.

### Modified Capabilities
(none — `case 0`/`case 1` already exhibit this behavior; this change extends the same behavior to the two currently-unguarded call sites, it doesn't change existing guarded behavior)

## Impact

- **受影响代码**：`app/src/main/java/com/sansen/yeweiyi/manager/SerialPortManager.java`（`case 2` 分支，约10行）；`app/src/main/java/com/sansen/yeweiyi/factory/VR350Factory.java`（`handleMessage` 内 `initData` 调用，约5-8行）。不改动任何 `factory/` 解析器内部逻辑或方法签名。
- **三条硬约束**：respects 约束1（无大爆炸重写，只在已有的两个调用点加 try/catch，复用 `case 0`/`case 1` 已验证过的写法）；约束2 不直接适用于新增的这类防御性包装本身（`SerialPortManager`/`VR350Factory` 都依赖 Android `Context`/`Handler`，不能在 JVM 上单测；且加 try/catch 不改变已测试覆盖的行为路径）；直接服务于约束3（协议层健壮性优先于 UI）。
- **需要真机验证**：本改动触碰 `factory/` 调用边界（`manager/SerialPortManager.java`、`factory/VR350Factory.java`），属于硬件/协议层——`assembleDebug`/单测只能确认编译通过和不影响既有测试，无法在本机验证真实探头/PC协议异常帧下的实际行为，上线前需要真机验证：至少人为触发一次 LD2Company 分支解析异常（例如临时输入畸形帧）与 VR350 PC 协议异常帧，确认应用不崩溃、不静默失效。
