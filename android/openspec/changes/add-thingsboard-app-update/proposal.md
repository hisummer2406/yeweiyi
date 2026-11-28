## Why

用户已于 2026-11-26 确认采用 ThingsBoard 实现液位仪应用内检查更新与下载，入口放在高级设置。设备现有 MQTT 身份和共享属性通道可以复用，但缺少 APK 更新客户端、安装后恢复和业务发布管理。

本变更是 Android 与 cloud 的联合实施计划，已按用户要求于 2026-11-28 开始实现基础模块，尚未部署。详细技术方案见 [design.md](design.md)，进度以 [tasks.md](tasks.md) 为准；代码状态见 [应用更新契约](../../../../cloud/docs/app-update-contract.md)。

实施代码基线为最新 main `ab1f6b91`，分支 `feature/thingsboard-app-update`。云端已有固件 OTA 历史实测；本次 SOFTWARE、HTTP 分块和 APK 真机更新仍待实际环境验收。

## What Changes

- Android 高级设置增加“软件更新”，支持主动检查、版本详情、下载进度、暂停/继续、取消、校验、用户确认安装和结果展示。
- 使用 TB **SOFTWARE** 包和原生 `sw_*` 属性；APK 二进制、摘要和单设备下载鉴权留在 TB CE。
- 手动检查与下载访问 TB 原生 HTTPS Device API；现有 MQTT 连接用于更新通知、版本能力和结果上报，不增加 MQTT 客户端。
- 新增有版本号的发布说明属性 `appUpdateManifest`，关联 TB 包、Android versionCode、显示版本及更新说明；与 TB 标准属性一致后才能更新。
- 增加独立的下载与安装状态管理、分块续传、持久化恢复、包名/版本/签名检查和安装后的启动确认。
- cloud 增加最小发布管理 API：包导入、查询、指定设备分配/撤销、状态查询；只有平台管理员可管理。首版不增加后台网页与小程序页面。
- Go 后端保存发布元数据、分配意图与审计，处理自身数据库与 TB 之间的部分成功和重启恢复；设备检查与下载不经过 Go 用户 JWT 接口。
- 部署增加 TB Device API 的 HTTPS 白名单代理、凭据路径日志脱敏、大包上传限额及离线交付说明。

首版按“用户手动发起、完整 APK、系统确认安装、单设备分配”实施。静默安装、系统固件升级、差分包、自动安装、自动回滚和批次发布调度列为后续能力。

## Capabilities

### New Capabilities

- `update/app-update`：高级设置入口、TB 检查与下载、安装验证、状态持久化及成功确认。
- `update/app-release-management`：服务端包导入、设备分配与撤销、鉴权审计、状态查询和部署隔离。

### Modified Capabilities

现有油罐遥测、报警、RPC、出入库和采集协议的行为契约不变。新增可选更新字段须在共享协议与 fixture 中登记，`protocol-v1.md` 原有 `fw_*` 保留其固件含义。

## Impact

| 范围 | 主要位置 |
|---|---|
| Android 页面 | `fragment/SetSeniorFragment.java`、`fragment/AppUpdateFragment.kt`、`res/layout/fragment_senior_set.xml`、新页面布局及各语言资源 |
| Android 业务 | 新 `logic/update/`、`manager/AppUpdate*`、`manager/ThingsBoardManager.java`、`activity/MainActivity.java`、启动恢复接入点 |
| Android 安装 | `AndroidManifest.xml`、新 PackageInstaller 适配和结果接收器、`app/build.gradle`、正式包发布检查工具 |
| cloud TB 网关 | `internal/tbclient/` 增加 SOFTWARE 包、multipart 上传、完整设备对象安全更新 |
| cloud 业务 | 新 `internal/ota/`、`internal/api/ota.go`、迁移与 sqlc 查询、鉴权/租户/删除契约测试 |
| 部署与契约 | `deploy/nginx-api.conf.example`、Compose 配置、协议文档、共享 fixture、离线包说明 |

遵守仓库三条实施约束：采用新增模块和少量接入点，避免整体重写；先建立关键契约与故障测试，再接入现有通道；采集与报警运行优先于下载体验。新类使用 Kotlin，保留 compileSdk/targetSdk 27 和 support library。

本功能会中断应用进程，必须在真实终端验收安装权限、安装后恢复、采集/报警/IO 行为和历史数据保留。编译、模拟器或 TB 云端测试均不能替代该验收。

## 计划边界与评审项

TB 选型与高级设置入口已经确认。SOFTWARE 通道、手动安装、发布权限、协议字段及 HTTP 地址配置为本计划的实施默认值；P0 联合评审负责冻结这些细节，不重新讨论选型。

P0 的技术验证可推翻具体实现假设，例如终端不支持所选安装会话或 TB 分块行为不符合预期；须先修订设计与验收，再继续对应任务。
