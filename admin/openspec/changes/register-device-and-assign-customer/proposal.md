## Why

`/ops/devices` 目前是占位页。平台管理员没有任何页面可以登记设备、指定客户，只能走 API 或 SQL；设备不登记，后端就不会采集和回补
（`internal/ingest`、`internal/rollup` 按业务库 `devices` 表遍历）。GitHub issue #1 要求补上这条运维路径。
`yeweiyi-cloud` 已合入幂等登记接口 `POST /api/devices`（cloud #49，Refs #48），前端现在可以直接对接。

## What Changes

- `/ops/devices` 显示真实设备列表（`GET /api/devices`），带加载态、空状态和错误提示。
- 新增「登记设备」：输入出厂编号、选择客户，提交 `POST /api/devices`；重复登记同一设备同一客户得到一致结果，不改变归属。
- 客户下拉读取 `GET /api/customers`；没有合适客户时可在表单内创建（`POST /api/customers`），创建后自动选中，不要求填写数字 id。
- 出厂编号按字符串处理并保留前导零；纯数字短码（如 `000001`）只显示待确认的完整编号，管理员确认后才能提交；格式不明的输入拒绝提交。
- 其他客户已拥有的设备提示冲突，不提供迁移入口；401/403 沿用现有处理。
- 提交防重：请求进行中不能再次提交。
- **不做**：`/ops/pairing-secret` 页面、二维码生成与打印、`pairingToken` 校验、跨客户迁移、小程序扫码绑定。

## Capabilities

### New Capabilities
- `ops-device-registration`: 平台管理员在运维控制台查看设备、登记设备并分配客户，含出厂编号规范化、客户选择/创建与冲突处理。

### Modified Capabilities
<!-- 无：openspec/specs/ 目前没有与设备登记相关的现有 spec -->

## Impact

- 代码：`src/pages/ops/devices/`、`src/services/ant-design-pro/`（新增 devices / customers 请求函数与类型）、
  新增 `src/utils/deviceCode.ts`，及对应测试；`CLAUDE.md` 的项目状态与目录说明。
- 后端：**不需要改动**。依赖 `yeweiyi-cloud` 已合入的 `GET/POST /api/customers`、`GET /api/devices`、`POST /api/devices`
  （均在 `origin/main`）。本地联调须用包含 cloud #49 的 `main`，当前 cloud 工作区在 `feat/tank-shape-attribute`，不含该接口。
- 依赖：不新增 npm 依赖。
- 未覆盖的依赖：设备列表行不含客户归属字段（`device_name/is_online/last_online_at/last_offline_at/updated_at`），列表不显示客户，
  如需显示要另行扩展 cloud；小程序扫码绑定的授权规则仍待产品/后端确认，本变更不宣称扫码闭环完成。
