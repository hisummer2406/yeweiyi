# 设备协议 V1 共享 fixture

这批文件是 **云端（`cloud/`）与设备端（`android/`）的共同契约**，不是云端的测试数据。
两侧的测试都应当读**同一批字节**：任一侧改了字段名或单位，两端的构建应该同时变红，
而不是等到真实设备上才发现。

> **为什么要有这份 README 和 `MANIFEST.json`。** 2026-09-01 两个仓库在同一天各自实现了
> "ThingsBoard 单栈设备协议 V1"，两边的 fixture 注释都写着"对方的还不存在，先自定义一套"，
> 结果彼此完全不兼容——设备发出的遥测云端一个字段都解析不出来。仅仅说一句"以云端目录为准"
> 挡不住这件事重演；能挡住的是一个双方都跑、且改动必须显式同步的校验。
> 完整经过见 [`../../../docs/protocol-v1-reconciliation.md`](../../../docs/protocol-v1-reconciliation.md)。

> **当前状态（2026-11-06 起）：两端共用这一份。** 合并为单仓库后，Android 单测直接读本目录（无副本），
> Android CI 与 Cloud CI 都会校验 `MANIFEST.json`，改 fixture 会同时触发两端测试。
> 限制：私有仓库没有分支保护，CI 变红不会在技术上阻止合并。Android 侧覆盖情况见 [`ANDROID.md`](ANDROID.md)。

**协议尚未冻结。** 仍待双方负责人确认的条目见
[`docs/protocol-v1-open-items.md`](../../../docs/protocol-v1-open-items.md)。
在那份清单清空之前，**不要往任一侧新增字段**。

## 文件

| 文件 | 作用 |
|---|---|
| `MANIFEST.json` | 每个 fixture 的 sha256、上下行方向、以及它钉住的是什么 |
| `verify-manifest.sh` | 校验器。只依赖 POSIX sh + awk + `sha256sum`/`shasum`，没有 Go/jq 依赖 |
| `*.json` | fixture 本身。`_comment` 键是给人看的文档，不属于协议，读取方一律忽略 |

## 怎么用

**云端（已生效）：** `go test ./internal/protocol/` 自动执行。
`manifest_test.go` 校验清单与文件一一对应、校验和一致，并且**每个 fixture 确实被某个测试读过**
——没有人读的 fixture 钉不住任何东西，只会悄悄烂掉。

**Android（已接入）：** `android/app/build.gradle` 把 `cloud/fixtures` 挂到测试 classpath，
`FixtureLoader.loadProtocolV1` 按 `/protocol/v1/<name>` 读取；Android CI 先跑 `verify-manifest.sh` 再跑单测。
Android 的契约测试逐 key 比对产出与 fixture，尚未缺口的 key 以 `GAP_*` 显式登记。

**手动：**

```sh
sh fixtures/protocol/v1/verify-manifest.sh
```

## 改一个 fixture

1. 改 `*.json`；
2. 重算并更新 `MANIFEST.json` 里那一条的 `sha256`
   （`shasum -a 256 <file>` 或 `sha256sum <file>`）；
3. 在同一个 PR 里改好两端代码，让 Cloud CI 与 Android CI 都通过。

第 2 步刻意没有做成自动生成。这点摩擦就是机制本身：它保证一次单方面的改名不会悄无声息地通过。

## 编写约定

- 单位写进键名（`...Mm` / `...L` / `...Kg` / `...C` / `...Pct`），不另开 `units` 对象。
  单独的 units 对象可能与实际数据不一致；写进键名则结构上不可能不一致。
- 罐数据用扁平键 `tank{n}.*`，不用嵌套数组。**这一条由实测定案**：嵌套结构下 ThingsBoard 的
  聚合不报错，而是静默返回 `0`，会让降采样产出贴着零的假曲线。复现步骤见对账文档 §A。
- 时间一律 epoch 毫秒 UTC，字段名带 `Ms` 后缀。
- 读取方必须忽略未知键（`telemetry_snapshot_forward_compat.json` 钉的就是这条）。
