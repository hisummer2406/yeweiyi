# ThingsBoard 应用内更新任务清单

设计日期：2026-11-26，实施更新：2026-11-28。基于 main `ab1f6b91` 的 `feature/thingsboard-app-update`，部分基础模块已实现；勾选只代表该条实际完成，见 [验证记录](../../../../cloud/docs/app-update-contract.md)。本清单统一覆盖 android/ 与 cloud/。

按最新 main 规则，本协作不编写/运行 Android 单测，下文 Android 测试任务交专门同事；本协作提供共享 fixture、测试设计交接和编译验证。真实 TB/终端未连接，P0 实机任务仍未完成，不据此开放安装或生产发布。

## 0. P0 契约与关键原型

- [ ] 0.1 【高，Android/cloud】盘点量产终端型号、Android 版本、当前包名/code/name/签名证书、默认 HOME 与厂商守护能力；记录可支持的升级起点。
- [ ] 0.2 【高，Android】在真实测试终端验证同签名 PackageInstaller Session：未知来源授权、PENDING_USER_ACTION、取消、明确失败、旧进程结束后的回调和新进程恢复；不操作现场生产机。
- [ ] 0.3 【高，Android】验证 Android 13 PendingIntent 可变性、接收器 exported/显式目标与 session/attempt 绑定；外部伪造结果不能改变更新成功状态。
- [ ] 0.4 【高，cloud】在专用 TB CE 4.2.1.1 测试设备验证 SOFTWARE 创建/上传/分配、sw_* 产生、HTTP 属性响应、错误 token、错版本、未分配、撤销；保留脱敏请求/响应。
- [ ] 0.5 【高，cloud/部署】使用大于 62 MiB 的真实包验证 size/chunk、多块拼接、最后一块、改配/撤销时响应，记录大小/延迟/内存；不假设 Range/206。
- [ ] 0.6 【高，Android/cloud】联合冻结 appUpdateManifest、appUpdateReport、能力字段、错误码、code 规则、分配 revision 与首版权限；登记 cloud/docs/protocol-v1-open-items.md 的责任人与待验收项。
- [ ] 0.7 【中，Android/cloud】冻结 HTTPS 地址/CA 配置、profile 无软件默认包约束、块大小/重试/限额默认值和离线内网部署方式。
- [ ] 0.8 【中，测试】先建立手写共享 fixture：合法候选、无分配、属性不一致、旧 code、未知 schema、禁用目标、成功/失败/旧 revision 报告；更新 MANIFEST 校验和，Android 复用同目录。
- [ ] 0.9 【待确认】若安装后不能自动恢复主界面/采集，确定最小 HOME 或厂商恢复方案并更新设计；P0 高风险项未闭合前不进入发布。

## 1. P1 cloud TB 网关与包导入

- [ ] 1.1 【高，测试优先】先为 TB 网关建立契约测试：完整设备读改写保留未知字段、409 重读、multipart 重开文件、401 重试、上传成功后响应丢失的读回识别。
- [x] 1.2 【高，cloud】实现 SOFTWARE 元数据创建/查询/上传/读回，元数据响应限 1 MiB，流式上传、独立 timeout、一次 401 重开文件；新旧 JSON 入口分离。Go 单测通过。
- [x] 1.3 【高，cloud】完整 TB JSON 更新 softwareId，保留未知字段及 TB version，409 最多 3 次重读；Go 单测验证保留与冲突分支。
- [ ] 1.4 【高，Android 工具】增加正式包描述生成/校验工具，解析 APK 包名/code/name/SDK/ABI、验证签名、计算 size/hash；拒绝 unsigned/debug 包，校验 code 比已发布版本高。
- [ ] 1.5 【高，cloud】实现有配额的流式暂存、哈希/描述核对、可重放上传及独立 timeout；覆盖超限、磁盘不足、取消与异常清理。
- [ ] 1.6 【中，cloud】包导入流程记录 TB packageId、读回摘要才置 ready；同 title/code 不同摘要拒绝，未知结果不盲目重建/删除。
- [ ] 1.7 【中，cloud】实现暂存目录启动清理与总配额，不在业务库长期存 APK 二进制；保留可诊断的导入失败记录。
- [ ] 1.8 【中，验收】跑真实 TB 上传/下载集成测试，记录实际镜像、包大小与结果，区分测试通过和基础设施缺失跳过。

## 2. P1 cloud 分配意图、API 与审计

- [ ] 2.1 【高，测试优先】先设计数据库/服务测试：角色变化、重复 requestId、expectedRevision 冲突、并发 worker、删除栅栏、部分成功重启恢复。
- [ ] 2.2 【高，cloud】新增 ota_releases、ota_device_assignments 的 up/down 迁移及中文注释、唯一/状态约束、SQL 写入守卫；实施时取最新迁移编号。
- [ ] 2.3 【高，cloud】为 allowed 审计保存 requestId/payloadHash/revision/目标并加去重索引与查询，独立结果审计按 revision 关联；旧请求重放返回历史结果或 superseded，不能重新派发。
- [ ] 2.4 【高，cloud】更新 db/queries/ota.sql，sqlc generate；迁移 down/up 往返验证，不手改 internal/store/db。
- [ ] 2.5 【高，cloud】意图和 allowed 审计同事务；实现租约/代次/每设备串行及有界重试，网络 IO 不持有长事务行锁。
- [ ] 2.6 【高，cloud】实现“禁用 manifest → softwareId → 等 sw_* → 启用 manifest → 读回”的分配流程；中途失败保留可恢复步骤。
- [ ] 2.7 【高，cloud】实现撤销“禁用 manifest → 清 softwareId → 核对继承”；profile 存默认软件包时显式拒绝或阻止完成。
- [ ] 2.8 【高，cloud】实现重启对账、操作超时后的读回判断和成功/失败审计；开关关闭不影响液位业务启动，不把未知结果标为已安装。
- [ ] 2.9 【高，cloud】新增平台管理员管理接口：导入/列表/详情、分配/撤销/设备状态；校验当前角色先于解析体和查设备，幂等重放也重新授权。
- [ ] 2.10 【高，cloud】接入设备删除残留清单、删除中的写入栅栏和换客户后的撤销；验证延迟 worker 不回写被删设备。
- [ ] 2.11 【高，测试】新增路由纳入 tenancy_test.go 的 deviceScopedRoutes/豁免分类，验证客户管理员、员工、跨客户、停用和角色变化。
- [ ] 2.12 【中，cloud】状态查询分别输出 desired/observed/deviceReport/freshness；完整 JSON 报告按 revision/attempt/seq 关联，区分无报告、不支持、过期、未确认和 TB 故障。
- [ ] 2.13 【中，cloud】登记稳定错误码、分页与配置项；给运维提供使用上述业务 API 的脱敏调用说明，首版不依赖 admin/miniapp 页面。
- [ ] 2.14 【中，验收】注入 TB 已成功但 Go 超时、manifest 写失败、旧 worker 迟到、重复请求和进程重启，确认最终收敛及审计一致。

## 3. P1 部署与大包通道

- [ ] 3.1 【高，部署】在 Nginx 示例增加严格 GET attributes/software 白名单到 TB，验证不放开控制台、登录、管理 REST、任意设备 POST。
- [ ] 3.2 【高，部署/测试】设备 location 独立脱敏 token 路径或禁 access log；检查 Nginx error log、Go/TB 日志、Android HTTP 异常和日志导出，不包含 token 原文。
- [ ] 3.3 【高，部署】管理导入路由单独放宽请求体和 timeout；Go MaxBytesReader、TB HTTP_MAX_PAYLOAD、multipart file/request 限额覆盖目标 APK，其他接口保留现有限额。
- [ ] 3.4 【中，部署】补 no-store、查询参数透传、私有 CA/域名配置；检查 /api/v1 路由优先级，不让设备下载落入 Go catch-all。
- [ ] 3.5 【中，测试】执行 nginx -t、Compose 检查脚本、真实大包导入/分块、边界超限、错误凭据与管理路径不可达测试。
- [ ] 3.6 【中，部署】补离线包配置/工具/操作说明、TB 包存储备份与业务库元数据一致性说明，保留 TB 固定镜像版本。

## 4. P2 Android 纯逻辑与状态持久化

- [ ] 4.1 【高，测试优先】新增 logic/update 契约测试，使用共享 fixture，覆盖严格类型/大小/schema、版本比较、无分配、禁用、属性交叉不匹配和身份代次。
- [ ] 4.2 【高，测试优先】新增状态机/恢复测试：重复点击、取消、迟到回调、目标变化、损坏状态、文件/checkpoint 长度差、安装回调缺失、旧终态晚到。
- [ ] 4.3 【高，Android】实现纯 Kotlin 的候选模型、解析、版本/兼容策略、任务标识、状态机与错误码；不引用 Context 或全局硬件单例。
- [ ] 4.4 【高，Android】新增私有 AppUpdateFileStore，原子写盘并传播失败，按身份和配置隔离；包含 install intent 与待发终态，不记录 token。
- [ ] 4.5 【高，Android】实现单任务 AppUpdateManager、独立 IO 执行器、代次校验及取消；不让 MQTT 回调、UI 和采集线程处理大文件。
- [ ] 4.6 【中，Android】新增 AppUpdateLog 领域日志并复用 CLOUD 模块预算；状态变化/失败可诊断，进度限频，错误清洗在写入前完成。
- [ ] 4.7 【中，验收】运行新模块 JVM 单测，核对真实测试报告；通过后才接入现有页面与 MQTT。

## 5. P2 Android HTTP、下载和更新页面

- [ ] 5.1 【高，测试优先】HTTPS/下载测试覆盖可信与错误主机证书、跨 origin 重定向、401/400/404/429/5xx、短块/超长块/HTML 错页、最后一块、低空间和取消。
- [ ] 5.2 【高，Android】新增 HTTP base URL 构建默认和持久化覆盖；云平台设置显示/保存该项，凭据或平台变化取消旧任务。保持现有 MQTT 配网行为。
- [ ] 5.3 【高，Android】装配 HTTPS 信任与标准主机名校验，覆盖内网 CA 与公网 CA；复用凭据存储，禁止明文/跳过验证与跨 origin 重定向。
- [ ] 5.4 【高，Android】实现属性新快照检查和 TB SOFTWARE 分块下载，精确校验块长度；fsync 后记录 checkpoint，恢复时截尾或重新下载。
- [ ] 5.5 【高，Android】实现完整包长度/摘要/包名/code/name/SDK/ABI/签名预检；失败清理不可安装文件，不自动卸载应用。
- [ ] 5.6 【中，Android】接入现有 ThingsBoardManager 的属性通知/删除变化，只触发候选刷新，不能影响 reportIntervalSec/RPC/业务事件顺序。
- [ ] 5.7 【中，Android】缓存 AppUpdateFragment 单实例；实现进度、暂停/继续/取消/删除包/重试和隐藏时解除观察；下载任务不依赖 Fragment 生命周期。
- [ ] 5.8 【低，Android】高级设置底栏增加软件更新入口，更新页可滚动内容与固定操作栏；使用 setOnClickListener，不引入 android:onClick 反射绑定。
- [ ] 5.9 【低，Android】补七种可选语言资源、说明回退与文案表登记；对当前实际横屏尺寸检查裁切、字号和长文本。
- [ ] 5.10 【中，验收】真机验证切页、重复进入、断网/断电恢复、MQTT 断线仍能 HTTPS 检查；下载和校验期间记录采集/报警行为。

## 6. P3 Android 安装与结果闭环

- [ ] 6.1 【高，测试优先】补安装恢复/启动确认判定测试：旧版仍在、目标已装、第三方版本、session 未完/已失败、状态写失败、伪造/迟到回调。
- [ ] 6.2 【高，Android】实现安装权限声明和用户授权跳转；返回后重新查询目标并复验包，不自动提交。
- [ ] 6.3 【高，Android】实现 PackageInstaller 写入/fsync/commit 与 sessionId/attemptId 耐久保存；确认取消和失败可再次手动操作，安装前持久化失败不提交。
- [ ] 6.4 【高，Android】实现接收器、PendingIntent 和回调校验，使用 P0 在实际终端验证的方案；不用公开 file URI。
- [ ] 6.5 【高，Android】检查数据库/业务事件写入和日志刷新屏障；无需等云端 ACK，不改业务数据和历史迁移策略。
- [ ] 6.6 【高，Android】接入 MY_PACKAGE_REPLACED 与实际启动路径，依据本地目标 code 和启动检查确认成功；安装回调缺失时查询 session/当前版本恢复。
- [ ] 6.7 【高，Android】若 P0 证明必要，以最小独立改动修正 HOME 返回多 MainActivity 实例问题，回归 onNewIntent/导航/采集初始化，不顺手重构主界面。
- [ ] 6.8 【高，Android】增加 AppUpdateReporter 与 OTA 专用耐久发件状态，reportSeq 跨重启递增；扩展既有 MQTT 确认回调并按 seq 清理，旧确认不能清新状态，不挤占报警队列。
- [ ] 6.9 【高，Android/cloud】能力/本机版本/完整 appUpdateReport 与 sw_state 同步规则落地；新启动只补发最新真实结果，旧 revision 不能覆盖新目标。
- [ ] 6.10 【中，验收】同正式签名引导包升级到更高 code，验证权限、取消、过程杀进程、断电、安装后离线启动和联网补报。

## 7. P4 联合验收与发布准备

- [ ] 7.1 【高，真机】验证原地更新保留罐配置/罐表、账号设置、设备凭据、报警/出入库/库存/交班历史；包含实际存在的旧数据库 schema 升级样本。
- [ ] 7.2 【高，真机】下载/校验期间接真实探头，验证采集、报警与声光；安装重启验证采集恢复和 IO2 输出，记录实际中断时间。
- [ ] 7.3 【高，真机/cloud】覆盖设计 §12 全部高优先级故障：篡改、错误签名/ABI、目标变化/撤销、token 轮换、掉电、安装后未启动和旧报告迟到。
- [ ] 7.4 【高，发布】确认正式签名与备份、唯一递增 versionCode、构建安全开关及发布描述；准备同签名更高 code 修复包路径，不将降级/自动回滚作为保证。
- [ ] 7.5 【中，集成】切断互联网，仅保留内网设备/TB/Go/代理，跑检查→分块→安装→启动确认→回报全流程。
- [ ] 7.6 【中，Android】本协作运行 `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.0.2.jdk/Contents/Home ./gradlew -Dhttp.proxyHost= -Dhttps.proxyHost= :app:assembleDebug`；专门同事负责 Android 单测及测试统计，安装另用正式同签名 release 验收。
- [ ] 7.7 【中，cloud】先跑新模块/协议测试，再 `go test ./... -p 1`、`go build ./...`、`go vet ./...`；列明真实 TB/PG 用例与跳过原因。
- [ ] 7.8 【中，文档】更新双方 CLAUDE.md 对实现状态的描述、protocol-v1.md 软件小节、baseline 实测边界、open-items、fixtures/MANIFEST、部署与离线更新手册；保留 fw_* 原语义。
- [ ] 7.9 【中，文档】生成联合验收记录：提交、镜像、硬件/系统、源/目标包签名与 code、用例结果、日志证据、未覆盖设备与恢复步骤。
- [ ] 7.10 【中，发布】按测试服务→引导 APK→更高 code 验证包→单台试点的顺序准备交付；发布/部署/提交仅按后续用户授权执行，不在计划阶段操作。

## 8. 后续迭代候选

以下不计入首版完成条件，也不提前实施：网页包管理和设备进度页、客户/批次滚动发布、静默安装/独立更新代理、定时维护窗口、差分包、自动回滚、整机系统升级。它们分别需要产品权限与终端能力设计。
