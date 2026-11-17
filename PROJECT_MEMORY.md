# 中成电气项目记忆

迁移日期：2026-11-09。用户明确要求把“中成前端”和“中成后端”的记忆迁入当前项目，两个旧项目不再使用。

## 项目归属与来源

唯一继续工作的项目是“中成电气”，路径 `/Users/stone/Developer/yeweiyi`。

| 旧项目或仓库 | 当前目录 | 说明 |
| --- | --- | --- |
| 中成前端：`/Users/stone/Developer/yeweiyi-admin` | `admin/` | 运维、客户桌面后台；旧项目聊天还包含小程序和 Donut 打包工作 |
| 中成后端：`/Users/stone/Developer/yeweiyi-cloud` | `cloud/` | 云端 API、业务数据库、ThingsBoard 集成 |
| `yeweiyi-miniapp` | `miniapp/` | 微信小程序及 Donut 客户端 App |
| `zhongchengyeweiyi` | `android/` | 液位仪安卓大屏设备端，与 Donut 客户端 App 是不同交付物 |

代码于 2026-11-06 合成单仓库，四个目录保留原仓库历史。旧路径和仓库名只用于检索历史；不要在那里继续开发。
原聊天、原始记忆摘要保留，本次迁移不删除或移动它们。历史来源索引见 [PROJECT_MEMORY_SOURCES.md](PROJECT_MEMORY_SOURCES.md)。

## GitHub 最新状态快照（2026-11-09）

本节在记忆迁移后，通过远端 Git 和 GitHub API 重新核对。时间按北京时间理解；GitHub 原始时间为 UTC，以下凌晨合并的 PR 在 UTC 仍为 11 月 8 日。后续变化须重新核查。

- 仓库：[hisummer2406/yeweiyi](https://github.com/hisummer2406/yeweiyi)，默认分支 `main`，主线最新提交 [`99f8c40`](https://github.com/hisummer2406/yeweiyi/commit/99f8c406edb40e296345a5ce5b6b1ff22a4570fc)。现有 origin 使用同仓库 HTTPS 地址，与用户提供的 SSH 地址指向同一仓库，未改远端配置。
- 已执行远端拉取，并将本地 `main` 从 `8250bd3` 快进到 `99f8c40`，与 `origin/main` 一致。
- 当前仍在 `admin/stock-manual-entry`，HEAD 为 `34dfb77`，与同名远端分支一致，包含最新主线；相对主线多 3 条提交，属于尚未合并的 PR #16。不要把当前工作树里已有的补录功能误报为主线已合并。
- 保留根目录记忆文档修改及原有 `admin/.claude/settings.json`、`miniapp/.claude/settings.json`；此次未切换功能分支、提交、推送、合并 PR 或触发部署。

### 已合并的代码

| PR | 已进入 main 的内容 | 验收边界 |
| --- | --- | --- |
| [#13](https://github.com/hisummer2406/yeweiyi/pull/13) | 云端配置提交保护：幂等、油站复核、在途互斥、设备级开关、整机设置镜像，含迁移 000038 | PR 报告真实 PostgreSQL、ThingsBoard、devicesim 检查通过；这次新增保护未真机验收，不表示已部署 |
| [#14](https://github.com/hisummer2406/yeweiyi/pull/14) | 后台参数设置、确认下发、操作记录及设备回显 | 未对真实云端做浏览器联调、未真机验收；最新主线 CI 失败见下 |
| [#15](https://github.com/hisummer2406/yeweiyi/pull/15) | 小程序储罐设置/参数修正下发、补录接入、操作记录 | PR 报告 typecheck/check 通过；未在 DevTools、Donut App、真实云端或真机运行验收 |
| [#17](https://github.com/hisummer2406/yeweiyi/pull/17) | 设备端整机多字段一次持久化，失败回滚缓存；订正配置字段文档 | PR 报告 JVM 测试和构建通过；真实写盘失败与剩余异常路径未验收 |
| [#12](https://github.com/hisummer2406/yeweiyi/pull/12) | 介质内置编号扩至 1–31、自定义 32–39；并入设备测试包 E–L（俄语/乌兹别克语、Modbus 网口等） | 旧固件 18–25 不能仅凭下标判成新内置介质，需名称匹配；不要继续套用旧编号范围 |
| [#10](https://github.com/hisummer2406/yeweiyi/pull/10) | Android 直接读取 cloud 唯一协议 fixture，新增 Android CI | fixture/单测不能替代真实硬件验收 |
| [#1](https://github.com/hisummer2406/yeweiyi/pull/1) | 设备统一日志、厂家设置页及标题栏 logo 定制 | PR 列有部分真机确认，另有声光、长期日志量、logo 等未验收项；不能概括成全部完成 |

### 未合并与仍开放的需求

唯一开放 PR 为 [#16 后台补录与撤销](https://github.com/hisummer2406/yeweiyi/pull/16)，分支即当前工作分支。补录按 `requestId` 幂等，仅云端补录记录可撤销、设备记录不可撤销，缺失量显示“—”；PR 明确未对真实云端浏览器联调。当前检查失败，不能当成可直接合并的验证结果。

GitHub 此次返回 8 个开放 Issue（不含 PR）。议题正文大多仍保留 11 月 4 日方案描述，不能用其“未实施”文字覆盖上方已合并 PR 的事实：

| Issue | 当前跟踪主题 | 继续工作的边界 |
| --- | --- | --- |
| [#2](https://github.com/hisummer2406/yeweiyi/issues/2) | 错误码与用户提示 | 当前仓库已有相关契约和实现，开放状态不等于完全未做，需按验收项核对 |
| [#3](https://github.com/hisummer2406/yeweiyi/issues/3) | 设备控制总方案 | 云端及配套代码已部分交付，联合验收和发布边界仍需跟踪 |
| [#4](https://github.com/hisummer2406/yeweiyi/issues/4) | 后台参数设置与操作记录 | #14 已合并，真实联调与 CI 阻塞仍在 |
| [#5](https://github.com/hisummer2406/yeweiyi/issues/5) | Donut Android 内部分发 | 保留旧 APK 交付历史；当前开放议题的剩余验收不能凭旧包推定完成 |
| [#6](https://github.com/hisummer2406/yeweiyi/issues/6) | 小程序配置下发与回显 | #15 已合并，目标客户端和真机验收仍在 |
| [#7](https://github.com/hisummer2406/yeweiyi/issues/7) | 完全离线 Android OTA | 仍开放；下载、校验、安装和回滚需独立真机验收 |
| [#8](https://github.com/hisummer2406/yeweiyi/issues/8) | 中成探针固定 50ms 等待问题 | 根治方案已设计，用户决定暂缓、等现场压力反馈；不能因同步信息就启动改造 |
| [#9](https://github.com/hisummer2406/yeweiyi/issues/9) | 设备远程配置与真机闭环 | #17 已合并，异常路径、整机写盘失败等仍待硬件验收；物理动作继续禁用 |

### CI 与发布记录

- **最新主线 Admin CI/CD 失败**：[run 42497796987](https://github.com/hisummer2406/yeweiyi/actions/runs/42497796987)，对应 `99f8c40`。lint 成功，测试断言 63 文件 / 762 项通过，但 `SettingsTab.test.tsx` 运行期间出现未捕获的 `ReferenceError: window is not defined`（React/scheduler 栈），导致测试步骤退出 1；后续构建和部署跳过。不是“全部检查已通过”。具体根因尚未修复或本地复现。
- **PR #16 同样失败**：[run 42497920253](https://github.com/hisummer2406/yeweiyi/actions/runs/42497920253)，对应 `34dfb77`。63 文件 / 764 项断言通过，但 `StockRecords.test.tsx`、`SettingsTab.test.tsx` 运行期间共出现 2 个相同未捕获异常，检查失败；不能仅引用 PR 正文先前的本机通过报告。
- 最近对应目录的 [Android CI 成功](https://github.com/hisummer2406/yeweiyi/actions/runs/42497783987)，SHA `f58fd81`；[Cloud CI 成功](https://github.com/hisummer2406/yeweiyi/actions/runs/42497762234)及 [Cloud Image 成功](https://github.com/hisummer2406/yeweiyi/actions/runs/42497762257)，SHA `ae92724`。各流程按目录触发，不能宣称它们都在主线最新 SHA 上重新跑过。
- 此次查询到最近一次成功 [Cloud Deploy](https://github.com/hisummer2406/yeweiyi/actions/runs/42490799479) 对应 `8250bd3`，早于 #13；因此 GitHub 发布记录没有证明 #13 已部署。生产可能有其他操作，本次未登录服务器核对，**不能据此断言当前线上镜像或数据库迁移版本**。

本节是 GitHub 与 Git 状态同步，本次没有修复 CI、重跑业务测试或进行线上验收。下一步如开展实现，应先处理已有 CI 阻塞，再按各议题验收边界推进；本节不授权合并或部署。

## 继续沿用的业务决定

- **客户和账号分离。** 客户是资产、数据归属单位，账号是登录身份，一个客户可有多个账号。分步开通顺序为“客户信息 → 客户管理员账号 → 油站 → 设备 → 确认开通”，并保留独立管理入口。设备在线、扫码、遥测和回补不是开通前提；配置中客户禁止业务登录，服务端校验不能被前端向导替代。来源：2026-10-12 客户开通讨论；当前契约 `cloud/docs/customer-onboarding-contract.md`。
- **三种角色。** 平台管理员、客户管理员、只读员工，不增加油站管理员。账号创建、启停、重置及员工罐授权均以当前云端契约为准，当前指南规定只有平台管理员能管理账号与写入罐授权；客户管理员限本客户资产管理，员工仅查看已授权罐。2026-10-12 早期讨论中“客户管理员可分配罐”的说法已被后续决定替代。来源：账号、罐授权讨论；当前 `cloud/CLAUDE.md`、`cloud/docs/tank-grants-contract.md`。
- **微信不自动注册。** 手机号和微信身份由服务端验证；微信不可用不应阻断离线管理员密码登录或服务启动。旧“员工绝不能有密码”也不是现行规则：当前指南允许平台管理员为手机号员工设置密码，并支持独立用户名账号。来源：2026-10-12 账号讨论；当前 `cloud/CLAUDE.md` 的账号规则与 #77 说明。
- **客户 → 油站 → 大屏 → 多个油罐。** 油站与大屏一对一，空油站允许；设备编码按字符串处理，保留前导零。换机使用新设备编码，不合并、接续、迁移或删除旧机历史，不复制旧授权。扫码只定位，不授予权限；旧 `user_devices` 不能替代罐授权。来源：2026-10-12 油站关系、2026-10-19 扫码排查；当前油站、罐授权契约。
- **我的记录跟随首页当前油站设备。** 不另设设备切换；出入库从设备详情移至独立记录入口。入库、出库、报警、库存、交班五类记录按可见罐筛选，字段顺序对齐显示器，竖屏允许横向表格滚动。交班和库存同步设备已有记录，不能由快照补造，缺失数值不能当成 0 或伪造，液位与水高单位为 mm。来源：2026-10-25 需求确认；当前 `cloud/docs/shift-stock-records-contract.md`。
- **首页可读性与罐图。** 已确认首页读数、标签、单位适老化，后台总览和详情每罐增加图示；液位填充缺陷指罐内彩色液体与罐身内轮廓的非设计缝隙。不能改变真实读数或涂满正常空罐区来掩盖缝隙。来源：2026-10-25 需求确认；这是需求来源，不表示本次验收通过。
- **设备彻底删除是单独的不可逆操作。** 仅平台管理员，覆盖业务库与 ThingsBoard，必须可追踪；换机、转客户不能顺带删除历史。本次记忆迁移不授权或执行设备删除。来源：2026-10-25 决定；当前 `cloud/docs/device-purge-contract.md`。

## 架构与控制边界

当前技术约束由各目录的 `CLAUDE.md` 管理。设备仅经 ThingsBoard CE 的原生 MQTT 接入，Go 服务提供业务 API、授权、命令状态机和审计；不要引入第二套 MQTT 通道或直接访问 ThingsBoard 数据库。

连续遥测以 ThingsBoard 为事实来源，业务库仅保存允许的小时汇总；报警、出入库等业务记录长期保存。目标环境未完成文档规定的回补核对前，不应凭旧记忆开启遥测 TTL。

远程控制先打通单个报警阈值配置，再扩展参数；泵阀、重启、消音、清警不能因页面或方法存在就当成可用功能。ACK 不代表完成，终态依赖异步 `cmdResult`；请求超时不代表设备没执行，不能盲目重发。配置镜像以设备上报为准，手机草稿不得静默覆盖设备值。

## 历史状态与本次核对到的更新

以下是带日期的证据，不是本次重新执行的测试、部署或线上健康核查。

| 日期 | 历史证据 | 使用边界或后续更新 |
| --- | --- | --- |
| 2026-10-14～15 | 修复过生产旧员工账号与数据库 CHECK 约束不一致；换 SSH 密钥后重跑过旧 cloud/admin 发布流程且成功 | 不保存个人手机号、密钥或凭据；不据此断言今天数据或 CI/CD 正常 |
| 2026-10-19～20 | 修复过 Compose 未向 API 注入微信配置、小程序误用本地调试地址；微信凭据连通性通过 | 当轮未验证真实有效授权码的完整登录；今天应重查当前部署与客户端配置 |
| 2026-10-19 | 用户表示小程序已验收，1.0.0 代码上传至微信开发版本 | 当轮未点击公众平台“提交审核”；不能推断已审核或上线，也不能把用户确认抹掉为从未验收 |
| 2026-10-19 | Donut 正式 Android APK 1.0.0/101 已生成，包名 `com.zhongchengelec.yeweiyi`，签名及包完整性已核验 | 旧产物在旧 `yeweiyi-miniapp/artifacts/releases/`；本次未核实产物是否仍存在。鸿蒙当时未产包，缺开发者账号、证书和 Profile |
| 2026-10-20 | 当次 ThingsBoard 查询未发现指定的第二台设备编号 | 仅当时快照，不能作为今天设备清单 |
| 2026-10-25 | 五类记录、适老化、单罐详情、草稿和彻底删除等需求已登记 | 后来已有实现，不能沿用“均未实现”；以当前子目录文档、代码及验收为准 |
| 2026-11-04 | 四仓设备控制实施方案发布；当时后台为空、小程序仅草稿、控制链路未真机验收 | 这三项状态已被下列 11 月 8 日记录部分更新 |
| 2026-11-08 | `admin/CLAUDE.md` 记录参数设置与操作记录已接入；`miniapp/CLAUDE.md` 记录远程配置、手机草稿隔离、操作记录及补录流程已实现 | 后台仅 mock/组件测试，未真实浏览器联调；小程序未 DevTools、客户端或真实云端/设备验收 |
| 2026-11-08 | `cloud/docs/tank-settings-device-verification.md` 记录一次真机单罐高液位阈值下发：ACK → `cmdResult` → 属性回显 → 重启持久化，并改回原值 | 仅覆盖文档所列 B1–B4 与重启；未覆盖 B5–B11、客户管理员、离线/超时路径。文档记录验证后已关回生产配置总开关，本次未查线上开关 |

当前配置契约还要求 `requestId` 幂等、`expectedStationId` 换机保护、部署和设备两级开关，以及未终态命令互斥。继续工作前查 `cloud/docs/config-command-contract.md`；历史“先做一个阈值”不构成打开控制开关的持续授权。

## 旧议题映射

这是根目录 `CLAUDE.md` 保存的合仓映射；最新议题状态见上方带日期的 GitHub 快照。旧编号必须带原仓库名；未列入映射的旧议题仍回原归档仓库检索。

| 原仓库议题 | 单仓库编号 |
| --- | --- |
| cloud#79 错误码与提示 | #2 |
| cloud#93 设备控制总方案 | #3 |
| admin#38 参数设置与操作记录 | #4 |
| miniapp#12 Donut Android | #5 |
| miniapp#26 真实配置与回显 | #6 |
| zhongchengyeweiyi#33 | #7 |
| zhongchengyeweiyi#50 | #8 |
| zhongchengyeweiyi#54 设备执行与真机验收 | #9 |
| zhongchengyeweiyi PR#48 | PR#1 |

## 工作偏好与后续使用

- 轻量实现、优先交付，复用已有功能；常规技术选择自行判断，影响核心规则、数据安全或确实阻塞时才询问。不要追加无关重构和反复验证。
- 旧跨仓库、多窗口做法是历史组织方式；现在以一个单仓库主线和各目录边界推进，需要并行时遵守当前用户及仓库指令，不默认启动多代理。
- 保留无关工作区变更；修改目标目录前先读其 `CLAUDE.md`。接口与协议变更仍按云端要求获得相关负责人确认。
- **仅用户要求时提交。** 历史任务中的提交、合并、部署或生产操作授权不自动延续；执行聚焦检查，如实报告通过、跳过和待真机验收项。
- Cloud Deploy 按根目录规则选择改过 `cloud/` 且 Cloud CI/Image 通过的 main 提交，不能复用旧仓库发布结论。

本次只迁移项目知识、保留检索索引并挂接阅读入口；未修改业务功能，未重跑业务测试，未提交、部署或改变旧聊天归属。
