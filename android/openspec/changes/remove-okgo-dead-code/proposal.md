## Why

`com.sansen.yeweiyi.okGoUntils`（`OkgoNet`/`RequestCallBack`/`MacUtils`/`ServerModel`，412 行）是一次未完成的调研遗留：当年评估用 OkGo 替换 xUtils `HttpUtils` 做网络请求，最终没有切换，`BaseActivity`/`LoginHelper` 实际用的仍是 `com.lidroid.xutils.http.callback.RequestCallBack`（不同的类，同名不同包）。逐词核查（`git grep -F`，每个符号单独查，不用 `\|` 交替——见 CLAUDE.md 的 ugrep 地雷说明）：

- `OkgoNet.getInstance()` 全仓库零处真实调用——4 处命中全部是**注释掉的代码**（`BaseActivity.java` 2 处、`AlarmDetection.java` 2 处）
- `MacUtils` 零引用（连注释里都没有）
- `ServerModel` 只在 `OkgoNet.java` 内部自引用（作为其请求的返回类型参数）
- `okGoUntils.RequestCallBack`（本包定义的接口，不是 xUtils 那个同名类）只在 `OkgoNet.java` 内部使用；`CrashAppLog.java:14-15` 导入了 `OkgoNet`/`RequestCallBack` 但从未在文件内使用——死 import

与刚合并的 `simplify-export-excel` 同一模式：不是"职责过多"，是"从未真正启用过的整层功能"。

**注意范围边界**：OkGo 三方库本身（`com.lzy.net:okgo:3.0.4`，`build.gradle:194`）**不是死代码**——7 个 Fragment（`AlarmFragment`/`CommitWorkRecortFragment`/`CreateOilTableFragment`/`InOilFragment`/`OilTestFragment`/`OutStockFragment`/`StockFragment`）静态导入 `com.lzy.okgo.utils.HttpUtils.runOnUiThread`，`CrashAppLog.java` 导入 `com.lzy.okgo.model.Progress`，这些都是库本身被直接使用，与 `okGoUntils` 这层未启用的封装无关。本次改动只删封装层，不动依赖声明。

## What Changes

- 删除整个 `app/src/main/java/com/sansen/yeweiyi/okGoUntils/` 包（`OkgoNet.java`/`RequestCallBack.java`/`MacUtils.java`/`ServerModel.java`，共 412 行，零调用方）
- 删除 `CrashAppLog.java:14-15` 两行死 import（`OkgoNet`/`RequestCallBack`）
- **不**删除 `build.gradle` 里的 OkGo 库依赖——库本身被 7 个 Fragment + `CrashAppLog` 直接使用，与本次删除的封装层无关
- **不**清理 `BaseActivity.java`/`AlarmDetection.java` 里引用 `OkgoNet` 的注释代码本身——只删被注释代码指向的死类；注释块的取舍不在本次死代码删除范围内，留给各自文件将来单独处理
- 不改变任何存活代码的签名、行为或输出格式——纯删除，不是重写

## Capabilities

（无——纯删除死代码，不改变任何可观察行为，无 spec 变更。`.openspec.yaml` 将设置 `skip_specs: true`。）

## Impact

- **受影响代码**：删除 `app/src/main/java/com/sansen/yeweiyi/okGoUntils/` 整个目录（4 个文件）；`app/src/main/java/com/sansen/yeweiyi/crashAppLog/CrashAppLog.java` 删 2 行死 import。
- **不受影响**：OkGo 库依赖（`build.gradle:194`）及其在 7 个 Fragment + `CrashAppLog` 里的真实用法；xUtils `HttpUtils`/`RequestCallBack`（`BaseActivity`/`LoginHelper` 实际使用的网络层）。
- **验证方式**：`assembleDebug` 确认删除后无遗漏引用（编译期比 grep 更强的保证）；不涉及 `serialport/`/`factory/`/`structbean/`，无需真机回归。
- **对三条硬约束的态度**：respects 约束1（纯删除，无大爆炸重写）；约束2 不直接适用（删除的代码从未被执行过，没有被测试覆盖的行为路径）；约束3 不直接适用（UI 层之外的网络封装死代码，优先级低于协议层，工作量小不与协议层抢资源）。
