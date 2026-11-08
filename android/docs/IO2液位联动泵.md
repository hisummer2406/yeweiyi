# 单罐开关量输出（IO2 液位联动泵）

2026-11-08 新增，分支 `feature/io2-level-pump`。编译和单测通过，**未经真机验证**。

## 1. 规则

绑定罐液位 < 低低液位时 IO2 闭合，闭合后保持到液位 > 高高液位才断开（回差）。比较方式和 `AlarmDetection` 的低低/高高报警一致。

| 情况 | 行为 |
|---|---|
| 开机 | 先写 IO2 = 0；液位在低低和高高之间时保持断开，降到低低才闭合（不保存上次状态） |
| 低低或高高为 0，或低低 ≥ 高高 | 断开，日志记一次"IO2联动阈值无效" |
| 探针断线（`errorTimes ≥ 5` 触发 `PRO_ERR_CONN`） | 断开 |
| 闭合状态下 60 s 没有有效读数 | 断开 |
| 关闭开关 / 更换绑定罐 | 断开并回到初始状态 |
| 演示模式 | 不驱动 IO2 |
| 接警 / 消警、IO1 报警灯 | 互不影响 |

IO2 写法按 IO1 报警灯（`SoundLightManager.openL_S`：`xzjhSetGpioValue(1, 1/0)`）推断为 `xzjhSetGpioValue(2, 1/0)`、写 1 闭合，**待真机确认**。

## 2. 代码

| 位置 | 作用 |
|---|---|
| `logic/pump/LevelPumpController.kt` | 纯逻辑回差判断，JVM 单测 `LevelPumpControllerTest` |
| `manager/PumpIoManager.kt` | 读设置、加锁、写 IO2、10 s 一次超时检查 |
| `applog/PumpLog.kt` | ALARM 模块日志；闭合/断开及原因记 E |
| `MyApplication.onCreate` | `PumpIoManager.init` |
| `SerialPortManager.hexDataParseFinish` | `onReading`：串口线程同步调用，不经过 `MainActivity`；演示数据走 `hexTestDataParseFinish`，不进来 |
| `SerialPortManager` 断线 `handler` | `onProbeLost` |
| `ChuanKouFragment` / `fragment_chuan_kou.xml` | 高级设置 → 串口设置页：开关图标（同记录设置页）+ 罐号框（点击弹 `ActivityDialog` 罐号选择） |

设置存 `SharePrefUtil.PUMP_LINK_ENABLED`（默认 false）、`PUMP_LINK_TANK`（0 = 未选）。未选罐号时打开开关会先弹罐号选择，选中才打开，取消则退回关闭。

文案：只新增 `pump_link_title`/`pump_link_desc`（en/es/ru/uz 为 AI 翻译，文案表已标红待 review）；罐号框未选时显示 `tank_no`，选后显示 `oilcanArray` 对应项。

## 3. 不做

- 不接云端 `pump.start`/`pump.stop`（仍 `FEATURE_DISABLED`），开关状态不上报云端。
- 不改 IO1 和现有报警逻辑。顺带看到的两个现有问题，暂不修：

| 级别 | 问题 |
|---|---|
| 中 | `SoundLightManager.closeAlarm` 不看其他罐是否仍在报警，任一报警恢复就关 IO1 灯和蜂鸣 |
| 中 | `AlarmManager.reshOilCan` 所有罐共用一个 `BeginRunable`，在 2 线程池里并发时可能拿到别的罐的数据 |

## 4. 真机验证清单

1. 开机 IO2 断开。
2. 未选罐号时打开开关先弹罐号选择；取消后开关退回关闭。
3. 液位 < 低低闭合；两者之间保持；> 高高断开。
4. 拔探针后断开。
5. 接警不影响 IO2；关闭开关立即断开；演示模式 IO2 不动作。
6. 中/英/西/俄/乌兹别克语下串口设置页三块都能完整显示。
