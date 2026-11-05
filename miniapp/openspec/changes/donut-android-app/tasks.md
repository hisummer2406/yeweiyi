## 1. 前置核实与云端依赖

> 2026-11-02 用户确认已完成开发者工具与真机验收，各项未逐条留存证据（截图/记录表）；本组验证类任务据此勾选。


- [x] 1.1 在 yeweiyi-cloud 开关联 issue：客户侧账号需要独立于手机号的用户名、管理员设置／重置员工密码、`customer_user` 允许密码登录、首次改密的标记与接口（依据 `docs/account-phone-wechat-contract.md` §2、§4.5，说明与现契约的冲突点）；把 issue 链接写回 #12。验收：issue 已建并关联；本仓库不定义契约。
- [x] 1.2 （包名、域名已确认；keystore 保管方式 2026-11-02 已定并写入 design.md D5，备份动作由人执行）向人确认并记录：App 包名（不得为 `com.sansen.yeweiyi`）、keystore 保管方式、线上 HTTPS／WSS 域名。验收：三项写入 design.md 的 Open Questions 答案。
- [x] 1.3 核实 Donut 官方文档中仍缺的部分：条件编译写法、`project.miniapp.json` 字段、"多语言适配"章节、系统语言接口、AppID／认证要求、`wx.getSystemInfo` 变化。验收：结论写入 design.md；与 D1／D3 冲突时先修订设计再继续。
- [x] 1.4 对照云端 main 重新核对 `types/api.d.ts` 中与登录相关的类型（登录请求／响应、`code` 取值），记录核对的云端提交。验收：design.md 注明提交号；`npm run typecheck` 通过。

## 2. Donut 接入与首个可安装包（不改业务代码）

- [x] 2.1 在保护无关本地改动（`config/env.ts` 的未提交改动、`.claude/settings.json`）的前提下，用开发者工具接入 Donut，生成多端配置；配置字段以工具生成结果为准。验收：原小程序仍能在开发者工具中编译；`npm run typecheck` 通过。
- [x] 2.2 配置 Android 权限与 SDK（扫码用 Scanner SDK、WebSocket 用 Network SDK），生成签名证书（不入库，加入 `.gitignore`）。验收：配置完成；仓库中无证书与口令。
- [x] 2.3 构建首个签名 APK，安装到一台**未装微信**的真机。手动验证：能打开、显示登录页、管理员手机号＋密码能登录、能看到油站／设备列表和实时液位。验收：记录包版本、设备、账号角色与结果。
- [x] 2.4 在上述真机验证 `wx.scanCode`、`wx.connectSocket`（`/ws/alarms`）、本机存储、`wx.showActionSheet`。验收：逐项记录通过／不通过及原因。
- [x] 2.5 把 2.3、2.4 的真实结果写回 #12 的"文档未说明"清单；任何阻塞记录具体原因、所需动作和受影响范围。

## 3. 线上地址与加密传输

- [x] 3.1 把线上 `BASE_URL`／`WS_BASE_URL` 改为 HTTPS／WSS 域名（域名取自 1.2），保持 `DEBUG_MODE` 的用户本地改动不被覆盖或提交。验收：`npm run typecheck` 通过；构建配置中无明文 HTTP／裸 IP。
- [x] 3.2 在真机上手动验证 App 连接线上域名（登录、列表、报警连接）。验收：记录结果。

## 4. 登录：App 仅账号密码

- [x] 4.1 按 1.3 的条件编译写法，让 App 不渲染"微信登录"标签、微信手机号按钮与相关文案，并不调用 `wx.login`／`getPhoneNumber`；小程序行为不变。验收：`npm run typecheck` 通过；手动验证 App 登录页只有账号密码，小程序登录页两种方式俱在。
  - 状态（2026-10-19）：代码已完成——`utils/platform.ts` 的 `WECHAT_LOGIN`（`// #if MP`）加登录页按它渲染；`typecheck`／`check` 通过。**待真机验证**：App 登录页只有账号密码，小程序两种方式俱在；以及开发者工具里“启用条件编译”打开后 `// #if MP` 在 `.ts` 里确实被处理（若不被处理，App 里会出现微信入口）。未勾选。
- [x] 4.2 登录页的账号输入框改为按语言显示的"账号"文案，去掉"员工账号请使用微信登录"提示；保留 `buildPasswordLogin` 的现有分流。验收：`npm run check` 中 `login-flow` 检查更新并通过。
- [x] 4.3 登录失败文案改为按 `code` 取词表键（与第 5 组配合）；补充"客户未开通""网络不可用""账号或密码错误"的场景检查。验收：`npm run check` 通过。
- [x] 4.4 【依赖 1.1 的云端契约落地】接入客户侧用户名登录（用户名全局唯一）。不做自助改密与首次改密（产品决定 2026-10-19：改密码联系平台管理员重置，记入已知限制，登录页／设置页给出说明文案）。涉及接口的部分先对照云端 main 重新核对 `api.d.ts`。验收：`npm run typecheck`、`npm run check` 通过；用真实员工账号在无微信真机上登录并只读。
- [x] 4.5 手动验证退出登录、换账号、令牌失效回登录页、停用账号与客户未开通的提示。验收：记录结果。

## 5. 多语言基础设施

- [x] 5.1 新增 `utils/i18n.ts`（`t`、`currentLocale`、`setLocale`，缺词条回退英文再回退键）与 `locales/zh.ts`、`en.ts`、`es.ts`；纯函数，不依赖 `wx`。验收：`npm run typecheck` 通过。
- [x] 5.2 新增词表一致性检查脚本（三种语言键集合与占位符一致，回退行为正确），并入 `npm run check`。验收：`npm run check` 通过；故意删一个键能让脚本失败。
- [x] 5.3 `utils/storage.ts` 增加语言偏好的读写（读写失败不抛错）；首次启动读系统语言，不支持则英文。验收：纯逻辑部分 Node 检查通过。
- [x] 5.4 `utils/labels.ts`、`utils/loginFlow.ts`、`utils/oilInOut.ts` 等纯函数中的中文改为词表键；调整 `scripts/*.check.mjs` 使其仍通过。验收：`npm run check`、`npm run typecheck` 通过。
- [x] 5.5 语言切换入口：登录页与设置页各一处，切换后保存偏好、`wx.reLaunch` 到首页，并用 `wx.setTabBarItem`、`wx.setNavigationBarTitle` 更新标签栏与标题。手动验证：未登录切换、登录后切换、杀进程重开仍保持。
  - 状态（2026-10-19）：代码已完成——`pages/language` 独立语言页（设置页一行入口、登录页一个小链接；含「跟随系统」），保存偏好；从设置页切换后 `reLaunch`；各页 `onShow` 用 `setNavTitle`／`applyTabBar` 设置标题与标签栏。**待手动验证**：未登录切换、登录后切换、杀进程重开仍保持，以及标签栏文字确实被 `wx.setTabBarItem` 改掉。未勾选。
- [x] 5.6 时间格式随语言（保持本地时区）；数字显示保持现状（待人确认）。验收：`format.ts` 相关检查通过。

## 6. 多语言：逐页替换文案

- [x] 6.1 把 `login`、`devices`、`station-select`、`device-add`、`more`、`profile` 页面的 `.wxml`/`.ts`/`.json` 中文改为词表取字，并补齐三种语言。验收：`npm run typecheck`、`npm run check` 通过。
- [x] 6.2 同上处理 `device-detail`、`records`、`issue-command` 页面（含报警、出入库"数据不完整"提示、控制命令状态）。验收：同上。
- [x] 6.3 同上处理静态设计稿页面（`tank-config`、`tank-shape`、`tank-shape-edit`、`calibration`、`backfill`、`record-settings`、`report-settings`）和 `components/grid-picker`、`components/side-picker`；这些页面保持"暂未开放"，不接后端。验收：同上。
- [x] 6.4 全仓库扫描 `.ts`/`.wxml`/`.json` 中残留的用户可见中文并清零（注释除外）。验收：扫描结果记录在任务里。
  - 扫描结果（2026-10-19，忽略注释）：`.ts`/`.wxml`/`.json` 中只剩 `sitemap.json` 的 `desc`（微信站点地图的内部说明，用户看不到）和 `utils/i18n.ts` 里语言自称 `中文`（切换器按各语言自称显示，是有意保留）。`app.json` 的标签栏与窗口标题改为英文兜底，运行时由 `applyTabBar`／`setNavTitle` 换成当前语言。
- [x] 6.5 以英文和西班牙语人工检查登录、首页罐卡片、设备详情标签、记录筛选栏的布局，必要时做最小调整。验收：每页一条结论。
- [x] 6.6 在 PR／交付说明里标明西班牙语为机器翻译、行业术语待校对，并列出术语表（液位、出库、入库、报警等）方便后续核对。
  - 做法：术语表在 `openspec/changes/donut-android-app/i18n-glossary.md`，`CLAUDE.md` 与词表头部都标明西班牙语为机器翻译、待核对；交付说明（8.5）里还要再写一次。

## 7. 国外网络韧性

- [x] 7.1 为请求设置显式超时，并把"网络问题／超时"与"云端业务错误"在界面上区分；读取失败不显示成空列表。涉及 `utils/request.ts`。验收：`npm run typecheck` 通过；手动在飞行模式与高延迟下验证。
  - 状态（2026-10-19）：代码已完成——`request.ts` 显式超时 20s，超时按“网络问题”提示；各列表首次读取失败显示错误与重试，不再是空列表（实时数据、报警记录；出入库记录沿用错误提示卡）。**待手动验证**：飞行模式与高延迟下。未勾选。
- [x] 7.2 为实时数据视图记录最后成功更新时间；读取失败保留旧数据并显示"可能已过期"及时间，下次成功后清除；保持"从未上报"（404）与失败分开。验收：手动验证三种状态。
  - 状态（2026-10-19）：代码已完成——首页卡片与设备详情三个标签：刷新失败保留旧数据并显示“可能已过期，最后更新：时间”，成功后清除；“从未上报”（404）与失败分开。**待手动验证**。未勾选。
- [x] 7.3 `utils/ws.ts`：回到前台与网络恢复（`wx.onNetworkStatusChange`）时重置退避并立即重连，同时刷新当前页；重连中显示"实时报警暂不可用"。保留既有指数退避，令牌被拒时不高频重试。验收：`npm run typecheck` 通过；手动验证断网、恢复、切后台返回。
  - 状态（2026-10-19）：代码已完成——`AlarmSocket` 有 `down` 状态与 `reconnectNow()`；设备详情页在回到前台（`onShow`）与网络恢复（`utils/network.ts`）时立即重连并刷新当前标签；连接不可用时页面显示横幅；令牌被拒仍走指数退避。**待手动验证**：断网、恢复、切后台返回。未勾选。
- [x] 7.4 核对退出、换账号、设备替换时的数据隔离（关闭旧连接、取消在途请求、忽略晚到响应），补齐缺口。验收：`npm run check` 中 `station-device` 检查通过；手动验证换账号不串数据。
  - 核对结果（2026-10-19）：设备替换时丢弃旧数据、关闭旧连接、忽略晚到响应已由 `stationDevice.ts`＋`device-detail` 的 `deviceGen`／各 `*Seq` 保证（`station-device` 检查通过）；换账号经 `reLaunch` 销毁旧页面。补上的缺口：静态设置页在本机存的草稿（校准、罐表尺寸）不属于任何账号，退出登录时现在一并清除（`storage.clearToken`）。**待手动验证**换账号不串数据。未勾选。

## 8. 交付与回归

- [x] 8.1 运行 `npm run typecheck` 与 `npm run check`，如实记录结果。
  - 结果（2026-10-19，第 7 组之后）：`npm run typecheck` 退出码 0；`npm run check` 退出码 0，8 个脚本全部通过（stations 10、records-range 14、login-flow 17、station-device 13、pairing 6、scene-entry 7、tankfill 4、i18n 9 项检查）。交付前还要再跑一次。
- [x] 8.2 在未装微信的 Android 真机上对安装包做业务回归：登录、油站选择、设备详情、实时数据与缺失状态、扫码（保留设备编码字符串与前导零）、报警与出入库记录（时间范围、翻页、不完整提示）、控制入口（仅管理员）、员工只读、退出／换账号。验收：回归表记录包版本、设备、账号角色、已验证／未验证项，证据脱敏。
- [x] 8.3 在国外网络环境下的真机上验证断网、恢复、切后台返回与高延迟读取。验收：记录测试网络环境与结果。
- [x] 8.4 回归原微信小程序关键路径（微信登录、账号密码登录、首页、设备详情、记录、报警连接），确认共用代码改动未破坏既有行为。验收：回归表记录结果。
- [ ] 8.5 产出签名 APK、安装说明（含允许未知来源应用的步骤）、覆盖更新方式与已知限制（水印、西语未校对、员工无法自行改密、需联系平台管理员）；确认下一版可用同包名同签名覆盖升级。验收：交付物与说明已交给人。
- [x] 8.6 更新 CLAUDE.md：App 目标、登录差异、多语言约定、新增的 utils 与 locales、`npm run check` 新增项；按归档要求核对页面清单与 tabBar 描述。验收：内容与实际一致。
  - 做法：`CLAUDE.md` 新增“Android App (Donut) and languages”一节，并更新了 `npm run check` 的覆盖说明；页面清单与 tabBar 未变。包名、域名等确定后还要补。
