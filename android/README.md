# 安卓液位仪

运行在专用横屏硬件终端上的原生 Android 应用，包名 `com.sansen.yeweiyi`，用于**油罐液位监测**。

设备通过 RS-232/485 串口与 TCP 套接字与液位探头通信，解析厂商私有二进制协议，把读数落到本地数据库，
同时在局域网内开放 HTTP 接口供 PC 端查询。报警（高低液位、超温、探头断线等）在本机声光提示并入库。

---

## ⚠️ 接手前必读

这个项目有三条与常规 Android 项目不同的前提，不了解会踩坑：

**1. 绿色构建只证明能编译，证明不了任何硬件行为。**
串口协议、探头解析、报警触发——这套系统真正的核心，在开发机上一行都验证不了。
凡涉及 `factory/`、`manager/SerialPortManager`、`serialport/` 的改动，**必须上真机回归**才算完成。

**2. 有一批"编译通过但运行时炸"的地雷。**
`android:onClick` 字符串反射绑定（34 处）、多个字节级工具方法的同名近义重载、
CRC 大小写与字节序陷阱等。动手前读 [`CLAUDE.md`](CLAUDE.md) 的 "Known landmines" 一节。

**3. 有 5 处已定位、已登记、但**未修复**的缺陷**，其中两处可能影响现场读数与报警送达。
见下方[已知未修缺陷](#已知未修缺陷)。**不要假设当前代码的行为都是正确的**——它是待确认的。

---

## 环境与构建

| 要求 | 版本 |
|---|---|
| JDK | **11**（Gradle 6.7.1 不支持 Java 16+，AGP 4.2.1 官方支持 8/11）|
| Android SDK | `platforms;android-27`、build-tools（30.0.3 可用）、platform-tools |
| NDK | 21.0.6113669 —— 仅在需要编译 `app/src/main/cpp` 原生代码时需要 |
| Gradle | 6.7.1（wrapper 自带，走腾讯云镜像）|

### 首次配置

`local.properties` 不进版本库，需要自己创建：

```bash
cp local.properties.template local.properties
# 按文件内注释填写 sdk.dir 与各项凭据（凭据找项目负责人索取，不要从 git 历史里翻）
```

缺任何凭据项，构建会打印 `[配置缺失]` 警告并把对应常量编译为空字符串——**应用仍能构建和启动，
但 MQTT 上报 / 接口加密会静默失效**。

### 常用命令

```bash
./gradlew :app:assembleDebug                                   # 构建 debug APK
./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest   # 跑单元测试（纯 JVM，无需设备）
```

> 跑测试**务必带 `cleanTestDebugUnitTest`**。输入没变时 `testDebugUnitTest` 会直接报 `UP-TO-DATE`
> 且不打印任何内容——过期的"通过"和新鲜的"通过"看起来一模一样。
> 或直接读 `app/build/test-results/testDebugUnitTest/*.xml`。

APK 输出名自动带版本号与日期：`app_NEW.SS.A.Y11.00_<日期>_debug.apk`。

---

## 模块

| 模块 | 说明 |
|---|---|
| `app/` | 应用本体，全部业务逻辑。`compileSdk 27` / `minSdk 21` / `targetSdk 27`，横屏锁定 |
| `xutilslibrary/` | 内置的旧版 **xUtils** 框架（ORM `DbUtils`、`HttpUtils`、注解注入），本项目的持久化与部分网络层 |

依赖是 `app/libs/` 下 **22 个本地 jar**（百度地图/TTS/推送、Apache POI、腾讯 X5、MQTT、AndServer、内部厂商 SDK 等）。
这些多数在 Maven 上没有对应发布，配套特定硬件——**不要尝试"升级"它们**。

---

## 架构

不是 MVVM，也没有 DataBinding/RxJava 优先的分层。当前形态是**围绕单例 Manager 组织**的传统 Android 应用
（早期从 MVVMHabit 模板脚手架而来，但模板已被完全取代）。

```
探头硬件 ──串口/TCP──▶ manager/SerialPortManager ──▶ factory/ 各厂商协议解析
                            (轮询编排)                   │
                                                        ▼
                                              bean/OilCan（读数）
                                                        │
                          ┌─────────────────────────────┼──────────────────────────┐
                          ▼                             ▼                          ▼
                  OilcanDataManager            manager/AlarmManager        db/ + DBDataManager
                   (内存态 + 刷新)              (报警判定/声光)              (xUtils 持久化)
                          │                             │                          │
                          ▼                             ▼                          ▼
                   fragment/ 各界面              SoundLightManager        web/handler/ 局域网接口
```

关键目录：

| 目录 | 职责 |
|---|---|
| `activity/MainActivity` | 唯一的实质 Activity，所有界面以 Fragment 形式挂在它下面 |
| `fragment/` | 实际界面：主页、油罐、出入库、报警、报表、设置等 |
| `manager/` | 单例管理器：串口、Socket、报警、油罐数据、持久化（`DBDataManager`）、定时任务 |
| `factory/` | **按厂商划分的协议解析器**——新增探头型号的扩展点 |
| `structbean/` | 协议帧的字节级结构定义（`*In.java`）|
| `db/` | POJO 实体，经 `db/help/DaoHelp.java` 用 xUtils `DbUtils` 持久化（不是 GreenDAO）|
| `web/handler/` | 局域网 HTTP 接口处理器 |
| `app/src/main/cpp/` | JNI 原生层：`serialport`（串口收发）、`atg`、`bspatch`（增量升级）|

> `app/src/main/java` 下还有若干**不在 `com.sansen.yeweiyi` 命名空间下**的顶层包
> （`serialport/`、`asynmsg/`、`storage/`、`rxjava/` 等，共 38 个文件）。它们确实在编译和使用中，
> 其中一部分是三方 vendored 代码（Apache Mina 的 IoBuffer、AIDL 生成的 IMountService 等）。
> 注意 `base.BaseFragment`（根包，基本是死代码）与 `com.sansen.yeweiyi.base.BaseFragment`（真正在用的）同名不同物。

---

## 局域网接口

设备启动后由 `service/CoreService` 拉起 AndServer，监听 **8080**：

| 路由 | 用途 |
|---|---|
| `/login` | 登录校验 |
| `/baseOilcan` | 油罐基础信息 |
| `/alarm` | 报警记录查询 |
| `/oilDischarge` | 出入库记录 |
| `/upload` `/download` `/image` | 文件与图片 |

> ⚠️ **当前这些接口零鉴权**，且 `/login` 校验的 `User` 表在设备初始化时被播种为默认口令。
> 加固方案见 `openspec/changes/harden-device-external-access/`。

---

## 测试

`app/src/test/` 下有 **57 个 JVM 单元测试**（其中 5 个在现场数据导入前按设计跳过），无需设备或 Robolectric：

| 测试类 | 覆盖 |
|---|---|
| `utils/CRCheckTest` | CRC 校验算法，含两处已固化的 API 陷阱 |
| `utils/DataUtilsTest` | 字节/十六进制工具方法，含已知重载差异 |
| `goldenmaster/GoldenMasterHarnessTest` | 现场报文录制格式、fixture 加载、快照比对器 |
| `goldenmaster/ProbeReplayTest` | 现场报文重放（等 `manifest.tsv` 有条目后自动生效）|

**测不了什么**：`factory/` 下的厂商协议解析器全部 `import android.content.Context` / `SparseArray`，
且内部调用 `MyApplication` 单例，纯 JVM 跑不起来。让它们可测正是当前重构的第一优先事项。

### 现场报文采集（golden master）

本机验证不了硬件行为，所以引入了「录制现场真实报文 → 带回来重放」的机制：

```bash
# 1. local.properties 设 sansen.probeRecording=true，重新构建安装
# 2. 设备在现场运行，报文写入 /sdcard/yeweiyi-probe-record/probe-<日期>.tsv
# 3. 取回并导入为 fixture
adb pull /sdcard/yeweiyi-probe-record/probe-20260901.tsv /tmp/
tools/prepare-probe-fixture.sh /tmp/probe-20260901.tsv 1 "LD探头正常工况24h"
./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest
```

录制开关**默认关闭**，出厂固件请保持关闭（它会持续写盘）。

---

## 已知未修缺陷

以下均已定位并登记，**尚未修复**——多数需要硬件/产品侧先确认才能决定改法。
完整分析见 [`docs/重构方案.md`](docs/重构方案.md) 第八节。

| # | 缺陷 | 影响 |
|---|---|---|
| 8.1 | `BackService.sendAlarms()` 失败仍返回 `true`，异常被吞 | 谎报成功（但见 8.5——这条路径够不着）|
| 8.2 | 探头协议分发 `case 1` **缺 `break`**，LD 结果被 LD2 覆盖 | 若现场 LD/LD2 共存，**当前读数就是错的** |
| 8.3 | `ProbeReciveDataListener` 全项目零实现，而调用点无条件解引用；`isStopProbe` 字段语义反转 | 特定路径下 NPE |
| 8.4 | LAN 登录接口接受出厂默认口令 | 局域网内任意主机可通过校验 |
| 8.5 | 报警上云的两条通道都是断的（`bindService` 被注释、`sendOilMsg()` 是空方法体）| **报警可能从未上云** |

其中 **8.2 与 8.5 可能是正在发生的线上问题**，优先级高于任何重构工作。

---

## 文档

| 文件 | 内容 |
|---|---|
| [`CLAUDE.md`](CLAUDE.md) | 代码库详解与工程约定：地雷清单、换行符规则、`git grep` 的坑、密钥注入机制 |
| [`docs/重构方案.md`](docs/重构方案.md) | 重构方案 v2（以可验证性为轴心）、缺陷登记、API 陷阱、**明确不做的事** |
| [`docs/小程序与PC端集成设计.md`](docs/小程序与PC端集成设计.md) | 小程序/PC 端对接设计 |
| `openspec/changes/` | 进行中的重构，每个含 proposal/design/specs/tasks 四件套 |

重构进度：

```bash
npx @fission-ai/openspec@latest list                    # 各变更任务进度
npx @fission-ai/openspec@latest validate --changes --strict
```

---

## 工程约定

- **换行符统一 LF**，由 `.gitattributes` 固化（`*.bat` 除外）。不要让批量脚本重写整个文件。
  `git blame` 查看 2026-08-07 之前的文件需加 `-w`，或一次性配置
  `git config blame.ignoreRevsFile .git-blame-ignore-revs`。
- **删任何东西前用 `git grep -F`（固定字符串）逐词核验**。本环境的 `git grep` 是 ugrep 后端，
  正则的 `\|` 与 `\b` 会**静默匹配为空并返回 0**——最坏的失败模式，已经因此误删资源搞挂过一次构建。
- **机械改动与语义改动不要放进同一个提交**。
- 仓库镜像走阿里云/腾讯云（大陆网络连通性），不要随手移除。

---

## 开发工作流

```
Issue
 ↓
Claude Code 创建 feature 分支
 ↓
Claude Code 修改代码
 ↓
提交 commit
 ↓
创建 PR
 ↓
Claude Code Review PR
 ↓
Merge
```

各步在本项目中的具体做法：

| 步骤 | 做法 |
|---|---|
| **Issue** | 结构化的重构工作走 `openspec/changes/` 的 tasks.md，不另开 Issue；零散缺陷与线上问题走 Issue |
| **创建 feature 分支** | 从 `master` 切出。不直接在 `master` 上提交 |
| **修改代码** | 遵守上方[工程约定](#工程约定)：删任何东西前 `git grep -F` 逐词核验；机械改动与语义改动分开 |
| **提交 commit** | 一次提交只做一件事，且**每个提交都要能单独装到设备上跑**。提交信息写清"为什么"，不只是"改了什么" |
| **创建 PR** | `gh pr create`，目标分支 `master` |
| **Review PR** | `/code-review` 审当前 diff，或 `/code-review <PR号>` 审指定 PR；`/code-review ultra` 是多智能体云端深度审查（由人触发） |
| **Merge** | 见下方合并前检查 |

### 合并前检查

```bash
./gradlew :app:assembleDebug
./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest
```

> ⚠️ **上面两条全绿也不等于可以合并。**
>
> 改动只要触及 `factory/`、`manager/SerialPortManager`、`serialport/` 或任何协议/串口路径，
> **必须先上真机回归**——本机验证不了硬件行为，这是这个项目最核心的约束（见[接手前必读](#️-接手前必读)）。
>
> 涉及现场读数的改动，还应在合并前跑一遍 golden master 重放测试确认零差异。

### 不适合交给 Claude Code 独立完成的

以下需要人来做决定，Claude Code 只负责查清事实并给出选项：

- **行为变更**（改返回值语义、补缺失的 `break`、改默认口令等）——需产品/硬件侧确认现场实际情况
- **真机回归**——只有接了真实探头的设备能验证
- **凭据轮换**——broker、产线等线上操作
- **现场数据采集**——档期不由开发侧控制
