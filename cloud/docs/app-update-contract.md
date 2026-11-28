# 液位仪 APK 更新契约与实施状态

日期：2026-11-28。实施起点：main `ab1f6b91`，分支 `feature/thingsboard-app-update`。
用户已确认 TB 选型并授权执行 Android/cloud 联合计划；任务见
[OpenSpec 清单](../../android/openspec/changes/add-thingsboard-app-update/tasks.md)。

## 当前代码

| 部分 | 实现位置 | 验证与边界 |
|---|---|---|
| 候选契约 | Go `internal/protocol/app_update.go`、Android `logic/update/AppUpdateContract.kt` | Go 契约测试通过；Android 编译通过，单测交同事 |
| TB 网关 | `internal/tbclient/ota.go` | SOFTWARE 创建/元数据查询/删除、流式上传、安全分配/撤销；单测通过 |
| 设备下载基础 | Android `manager/AppUpdateHttpClient.kt`、`AppUpdateDownloader.kt` | HTTPS 检查、分块、取消、checkpoint、摘要和目标复核；编译通过，未经真机验证 |
| 实际 TB 验收 | `internal/tbclient/ota_e2e_test.go` | 已提供用例，本机无 TB，实际通道验收跳过 |
| 管理 API、分配意图与审计、页面、安装和回报 | OpenSpec 后续任务 | 尚未接入，当前不向设备上报 OTA 能力，不自动执行更新 |

TB 网关是内部库，不能替代待实现的平台管理员 API、发布审计或完整撤销。
完整摘要校验也不代表 APK 包名、签名、ABI 和系统安装验证已完成。

## 候选输入

设备以 HTTPS GET `/api/v1/{token}/attributes` 查询 `AppUpdateSharedKeys` 指定字段，解析 `{ "shared": { ... } }`。
只接受明确配置的 HTTPS origin，不带路径前缀、query、fragment 或用户信息；不随重定向转发 token。
默认使用系统 CA，私有部署由调用方显式提供 CA，保留标准主机名检查。

| 标准属性 | V1 约定 |
|---|---|
| `sw_title` | 固定 `com.sansen.yeweiyi` |
| `sw_version` | 实际 Android versionCode 的十进制字符串，无前导零，1–2147483647 |
| `sw_size` | 严格整数，1–134217728 字节 |
| `sw_checksum_algorithm` | `SHA256` 或 `SHA-256` |
| `sw_checksum` | 完整文件 64 位 SHA-256 十六进制摘要，比较忽略大小写 |
| `appUpdateManifest` | JSON 对象，与标准属性一致后才能成为可更新候选 |

manifest 包含：`schemaVersion=1`、`enabled` 布尔、`releaseId`（1–64 位字母/数字/下划线/连字符）、
`tbPackageId`（UUID）、`assignmentRevision`（1–9007199254740991）、`title`、`versionCode`、
`versionName`（1–128 UTF-8 字节）、`packageName`、`sizeBytes`、`sha256`、`minSdk`（1–1000）、
`supportedAbis`（1–8 个不重复 Android ABI）、`releaseNotes`（最多 16 个语言，文本总计 16 KiB）、
`releasedAt`（带时区、精确到秒的 RFC3339）。完整属性响应上限 64 KiB，JSON 容器嵌套最多 32 层。

title/packageName 必须匹配当前应用；code/size/hash 必须与标准属性一致。说明按当前语言、基础语言、英文、中文回退。
字段名精确区分大小写，其他拼写按未知扩展忽略；未知 schema 拒绝。禁用墓碑只要求有效 schema 和显式 enabled=false；MQTT 局部推送不能当完整候选。

| 解析状态 | 含义 |
|---|---|
| `AVAILABLE` | 一致且 code 更大，可由用户开始下载 |
| `CURRENT` / `OLDER` | 相同 code / 目标更旧，不安装 |
| `NOT_ASSIGNED` / `DISABLED` | 没有更新字段 / 显式禁用 |
| `INFO_PENDING` | 字段缺失或两份元数据不一致，等待同步 |
| `UNSUPPORTED_SCHEMA` / `INVALID_INFO` | 不支持或非法信息，拒绝更新 |

手写共享 [fixture](../fixtures/protocol/v1/app_update_cases.json) 包含 15 种情况，Go 测试实际消费。
Android 单测由专门同事接手，本轮没有新建或执行 Android 单测。

## 传输和持久化

- 下载用 TB SOFTWARE 的 size/chunk，每块最多 1 MiB，精确验证末块长度，不假定 HTTP Range。
- 单块最多 3 次有限重试；401/403、目标和长度错误不盲目重试。取消可中断当前请求与重试等待。
- 私有 checkpoint 绑定 endpoint/设备/凭据摘要、release/revision、size/hash 和块大小，不保存 token。
- 完整块 fsync 后原子提交 checkpoint；恢复时先截掉无效或未提交尾部，再检查可用空间。文件变短时回到完整块边界；摘要失败时截空错误文件并同步归零 checkpoint。
- 完成后核对长度和完整 SHA-256，再次查询目标；verified.apk 只说明传输验证完成，后续仍需 APK/安装校验。
- 身份变化的全局取消与页面生命周期由待实现的 AppUpdateManager 接管，当前基础库不注册该监听。

Go 上传使用已校验暂存文件、固定 Content-Length 和分段 reader，不将完整 APK 放入内存。
401 后最多重新登录一次，重开文件重新核对摘要；其他失败不自动重试有副作用的上传。
上传超时是否实际成功由后续发布服务读回对账，不能根据客户端超时断言 TB 未保存。
OTA 元数据响应上限 1 MiB；上传独立 timeout 默认 3 分钟，可由 `tbclient.Config.OTAUploadTimeout` 覆盖。
设备分配使用完整 TB JSON 读改写，要求有效的整数 version，409 最多尝试 3 次；底层撤销写 softwareId=null，profile 默认包继承检查仍待后续服务实现。

## 验证和交接

审查修正后 Go 协议/TB 网关完整回归：顶层用例 79 通过、19 跳过、0 失败。跳过项需要本机没有运行的 TB，包括新增 SOFTWARE 验收。
OTA 定向 race 检查顶层 16 通过、1 跳过、0 失败；跳过的是实际 TB 通道验收。
Android `:app:assembleDebug` 通过，未运行单测；cloud 全项目 build 通过，vet 覆盖改动的两个包。

隔离 TB 就绪后，在 cloud/ 执行：

```sh
go test ./internal/tbclient -run '^TestOTARealSoftwareDistribution$' -count=1 -v
```

用例创建测试设备和软件包，验证上传/元数据读回/属性/分块/摘要/错凭据/错版本/撤销，清理自己的资源。
默认数据约 2 MiB，是合成二进制，仅验证分发，不证明 APK 可安装。
配置上传限额后加 `OTA_LARGE_TEST=1` 可验证约 65 MiB 合成大包。

Android 同事接手：15 个 fixture、严格 JSON/嵌套上限、CA/主机名、URL 脱敏、并发取消、短块/超限、checkpoint 损坏/截断/掉电、目标变化。
真实终端安装、回报和采集恢复在对应模块接入后验收。

## 本轮审查修正

以下问题已在本次代码中修正。

| 级别 | 问题 | 修正与证据 |
|---|---|---|
| 高 | JSON 无较小嵌套上限，Android 默认解析宽松，存在异常输入进入递归解析的风险 | 两端限制 32 层，Android 使用严格流式读取；Go 深度回归先复现后通过，Android 编译通过 |
| 中 | Go 将大小写不同的字段当作同一个结构字段，与 Android 结果不一致 | Go 只选择规范键后解码，按真实字段顺序的回归先复现后通过 |
| 中 | TB 返回无效 version 时仍发起设备更新，缺少乐观锁前提 | 缺失/null/字符串/小数/负数均禁止 POST；五个分支回归先失败后通过 |
| 中 | 同一 HTTP client 并发请求覆盖 activeCall，取消可能遗漏请求 | 串行化 GET 与取消前后检查，避免句柄覆盖；Android 运行验证交同事 |
| 中 | 无效 partial 尚未回收就检查空间，摘要失败后可能无法重试 | 先截断并 fsync，再检查空间；摘要失败同时清空文件与 checkpoint，待 Android 同事验证低空间重试 |

设备字段保留测试同时改为逐值比较，不再仅检查字段存在或包 ID 子串。

独立复审另发现低优先级日期校验缺口：Go 仍接受 `+24:00`、`+00:60` 等非法偏移。本轮按用户指定只修复空间恢复，时区上界和共享反例留待后续处理。
Android 验收补充：128 MiB 目标、初始可用约 350 MiB，完整下载后模拟摘要失败，再次下载应先释放错误文件，不被旧文件占用阻断；同时覆盖目标切换和 checkpoint 损坏。
