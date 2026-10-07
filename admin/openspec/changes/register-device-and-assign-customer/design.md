## Context

- `/ops/devices` 是占位页（`<PageContainer title="设备全量视图" />`），路由与 `canPlatformAdmin` 已就位；登录与 Bearer 附加已完成
  （`align-login-with-cloud`），`src/services/ant-design-pro/` 目前只剩 `login`。
- cloud 已在 `origin/main` 合入 `POST /api/devices`（cloud #49）：平台管理员、请求 `{deviceName, customerId}`；不存在则创建，未分配则认领，
  同客户重复登记返回 200 `created:false`，其他客户已拥有返回 409；`customerId` 不存在或编号含 `/ ? # \` 返回 400；非平台管理员 403。
  云端只 trim，**不强制前缀或长度**，且不触碰任何凭据。
- 设备列表行为 snake_case：`device_name/is_online/last_online_at/last_offline_at/updated_at`，没有客户字段；客户为 `{id,name,createdAt}`。
- 本仓库测试为 vitest + happy-dom，现有测试以纯逻辑和 mock 为主；`src/services/ant-design-pro/**` 不计覆盖率。

## Goals / Non-Goals

**Goals:**
- 管理员在页面完成「选客户 → 登记设备」，无需 API/SQL。
- 编号处理、错误归类、防重等逻辑放在纯函数/小模块里，可单测。
- 请求函数的类型与路径只来自 cloud 真实代码。

**Non-Goals:**
- 配对密钥、二维码、`pairingToken`、`/ops/pairing-secret`；跨客户迁移；列表显示客户归属；小程序扫码绑定及其授权规则；
  把 `ZCT` 前缀或位数写成产品协议；联调与上线。

## Decisions

1. **登记请求直连 `POST /api/devices`，不再设「契约待定」降级。** 原计划因接口未落地而设适配层和禁用按钮；接口已合入，
   保留降级只会留下死代码。`registerDevice` 仍集中在 `services/ant-design-pro/devices.ts`，路径只在此处出现。
   备选：沿用 pairing-secret 兼容登记——被 issue 明确否决（每次调用会轮换密钥）。
2. **按 HTTP 状态归类结果，而不是按文案。** 服务层把响应转换为 `{kind:'created'|'exists'}` 或抛出带 `status` 的错误；
   页面用一个纯函数 `describeRegisterError(status, error)` 映射为提示（409 冲突、400 显示后端 `error`、403 无权限）。
   登记请求设 `skipErrorHandler: true`，由表单自己提示，避免全局 `message.error` 与表单提示重复；401 由全局处理回登录页，
   所以 401 需要在 `skipErrorHandler` 下仍走清 token 逻辑——实现时先确认该行为，必要时在调用处显式转交 `handleRequestError`。
3. **编号规则集中在 `src/utils/deviceCode.ts`，返回判别联合。** `{status:'full'|'needsConfirm'|'invalid', code, reason?}`。
   默认前缀是具名常量并注明待确认，不入协议；云端放行任意字符串，所以前端校验只负责拦截明显错误，宁可拒绝也不猜。
   备选：只 trim 后原样提交——会让 `000001` 这类短码直接成为设备名，登记出无法被采集匹配的设备。
4. **提交防重用 `useRef` 标志 + 按钮 loading。** 只靠 state 在同一帧内连点会漏；ref 立即生效，finally 中复位。
5. **客户内联创建放在登记表单内，创建后刷新并选中。** 在 `Select` 的下拉底部放名称输入框和「新建客户」按钮（`popupRender`），不跳页、不丢已填编号。
   客户列表在表单挂载时拉取；创建成功后重新拉取（失败则本地追加）并 `setFieldValue('customerId', id)`。
6. **列表用 `ProTable`（`request` 取 `GET /api/devices`，`search={false}`）。** 后端没有分页/过滤参数，不做假分页；登记成功调用 `actionRef.reload()`。
7. **文案走 i18n（`zh-CN` 与 `en-US` 的 `pages.ts`、`menu.ts`）。** 现有页面均用 `pages.*` 键；菜单项 `menu.ops.devices` 原为「设备全量视图」，改为「设备管理」。
8. **测试范围。** 单测覆盖 `deviceCode`、`describeRegisterError`、服务层响应转换（mock `request`）、客户 id 必填校验；
   页面交互（防重、确认短码、内联创建后选中）用 `@testing-library/react` 在 happy-dom 中验证，Umi 依赖用 `vi.mock` 隔离。
   若某项因 Umi 运行时无法在 vitest 内稳定验证，则把逻辑下沉到可测模块并在交付说明里如实标注，不勉强造假测试。

## Risks / Trade-offs

- [前缀 `ZCT` 与短码规则未被产品确认] → 常量集中、注释标注；短码必须由管理员确认，完整编号可直接输入，不改变云端。
- [`skipErrorHandler` 可能绕过 401 的统一清 token] → 实现首步先写测试验证；失败则在调用处转交全局处理，不重复提示。
- [列表无客户列，管理员看不到设备归属] → 登记成功提示里显示所选客户；完整展示需 cloud 扩展，已在 proposal 的 Impact 里列为未覆盖依赖。
- [cloud 本地工作区在 `feat/tank-shape-attribute`，不含 #49] → 联调须切到含 #49 的 `main`；本变更不做联调，交付说明如实标注。
- [同名设备并发登记] → 由 cloud 单语句插入保证收敛；前端只保证单页内不重复提交。
