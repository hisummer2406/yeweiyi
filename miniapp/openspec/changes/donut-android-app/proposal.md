## Why

国外用户不使用微信，无法使用现有微信小程序。需要把现有小程序用 Donut（微信小程序多端框架）交付为可直接安装的 Android App（GitHub issue #12，2026-10-19 讨论后已更新）。

现有登录以微信为主：员工（customer_user）没有密码，只能微信手机号授权登录；账号密码登录只认大陆手机号和平台管理员用户名。这两条路在没有微信的手机上都走不通，所以"能打出 APK"不等于"国外用户能用"——登录、语言和国外网络是这次变更真正要解决的事。

## What Changes

- 用 Donut 把现有代码构建为 Android 签名 APK，直接分发安装；不上商店，首轮不做 iOS。免费版带 "Powered By Donut" 水印，已接受。
- App 内不出现任何微信入口（微信登录、手机号授权）；登录页只保留账号密码。原微信小程序的登录方式不变。
- 账号标识为管理员统一分配的用户名，员工的初始密码由管理员设置；用户名全局唯一；不做自助改密与首次改密，改密码一律联系平台管理员重置（产品决定 2026-10-19）。
- 界面支持多语言：首批中文、英文、西班牙语，文案做成按语言分开的词表，缺词条回退英文；默认跟随系统语言，登录页与设置页均可手动切换。西班牙语由 Claude 翻译，上线后再校对。
- 云端返回的错误按 `code` 映射为当前语言文案，不直接显示云端文字。
- 针对国外网络（云端在阿里云大陆区域）加强断线重连与"数据过期"提示，并要求国外网络下的真机验证。
- 线上地址改为 HTTPS 域名（现 `config/env.ts` 的线上地址是明文 HTTP 的 IP）。
- **BREAKING（云端前置条件）**：yeweiyi-cloud 需要让客户侧账号拥有独立于手机号的用户名，并支持管理员为员工设置／重置密码。依据 `yeweiyi-cloud/docs/account-phone-wechat-contract.md` §2、§4.5：现契约规定客户侧 `username` 等于手机号、手机号必填且仅限大陆号、`customer_user` 不得有密码，这些都与本需求冲突。本仓库不定义最终契约，需另开云端 issue。

## Capabilities

### New Capabilities
- `android-app-delivery`: 以 Donut 构建、签名、安装、更新 Android App；App 与小程序共用代码时的行为分界；构建额度与分发方式的约束。
- `app-password-login`: App 内仅账号密码登录（用户名＋密码）、改密码联系平台管理员的说明、无微信入口、各类登录失败状态的提示。
- `app-i18n`: 多语言词表、默认语言与手动切换、缺词条回退、云端错误按 `code` 本地化、日期时间与数字按语言显示。
- `app-network-resilience`: 国外网络下的请求超时与失败提示、"数据过期"提示、实时报警连接的重连与前后台恢复、账号／设备切换后的数据隔离。

### Modified Capabilities
<!-- openspec/specs/ 目前为空，没有既有 capability 需要修改。 -->

## Impact

- 构建与配置：新增 Donut 的 `project.miniapp.json` 等配置（字段以开发者工具生成结果和官方文档为准，不猜测）；`project.config.json` 的 AppID 是否可用于多端待核实；签名 keystore 不入库。
- 页面：`pages/login` 按平台区分入口；所有页面与 `app.json` 的 tabBar／导航栏文字改为从词表取字；`pages/more`、`pages/profile` 增加语言切换入口（无改密入口）。
- 代码：`utils/auth.ts`、`utils/loginFlow.ts`（失败文案按 `code` 走词表）、`utils/labels.ts`（改为词表）、`utils/request.ts`、`utils/ws.ts`、`utils/storage.ts`（语言偏好）、`utils/format.ts`（时间格式）、`config/env.ts`；新增语言词表与词表键一致性检查脚本（并入 `npm run check`）。
- 云端：依赖 yeweiyi-cloud 的账号模型改动（见上）。`types/api.d.ts` 待云端契约落地后同步。
- 对原小程序：共用代码的改动（词表、网络韧性）会同时影响小程序，需回归原小程序关键路径。
- 不在范围内：应用商店上架；iOS 交付；锁屏／退出 App 后的系统推送；App 内的微信登录、短信验证码、邮箱登录；云端尚无契约的校准、罐表下发、补录、报表导出；`tank-config` 等静态设计稿页面的后端接入；UI 重设计、改用其他框架重写；西班牙语行业术语的专业校对（上线后再改）；未经单独授权的生产发布或生产数据修改。
