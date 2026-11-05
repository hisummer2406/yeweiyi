# console-asset-management Specification

## Purpose
「资产管理」顶层菜单组（客户管理·油站管理·设备管理）的信息架构与角色可见性：设备页合并登记、客户归属与油站归属，分配油站只有一个入口，登记成功后同页分配油站；运维分区精简与旧地址重定向。

## Requirements

### Requirement: 「资产管理」菜单组
系统 SHALL 提供与「设备监控」并列的顶层菜单组「资产管理」，路由前缀为 `/console/assets/*`，按层级顺序包含三项，菜单名统一为四个字：客户管理（`/console/assets/customers`）、油站管理（`/console/assets/stations`）、设备管理（`/console/assets/devices`）。
整组 SHALL 只对 `platform_admin` 与 `customer_admin` 可见并可直接访问（`access.canManageAssets`）；「客户管理」项 SHALL 只对 `platform_admin` 可见并可直接访问。
菜单隐藏之外，直接输入地址 SHALL 同样被拦截（显示 403），不能只靠隐藏菜单。

#### Scenario: 平台管理员看到完整三级
- **WHEN** 平台管理员登录
- **THEN** 菜单出现「资产管理」，依次为 客户管理、油站管理、设备管理，且与「设备监控」并列

#### Scenario: 客户管理员看不到客户项
- **WHEN** 客户管理员登录
- **THEN** 「资产管理」只有 油站管理、设备管理 两项；直接访问 `/console/assets/customers` 显示 403

#### Scenario: 客户用户没有资产管理
- **WHEN** 客户用户（`customer_user`）登录
- **THEN** 菜单没有「资产管理」；直接访问任一 `/console/assets/*` 地址显示 403

#### Scenario: 组入口落到第一个可见项
- **WHEN** 访问 `/console/assets`
- **THEN** 平台管理员被重定向到客户页，客户管理员被重定向到油站页

### Requirement: 客户管理页面位置
客户的列表、新建、重命名、删除 SHALL 由 `/console/assets/customers` 提供，行为与此前的 `/ops/customers` 完全一致（内置 `default` 客户不可改名与删除、删除仅限没有账号/设备/历史的客户、冲突的 409 提示不变）。
旧地址 `/ops/customers` SHALL 重定向到新地址。`/ops` 菜单 SHALL NOT 再显示「客户管理」。

#### Scenario: 旧地址重定向
- **WHEN** 平台管理员访问 `/ops/customers`
- **THEN** 被重定向到 `/console/assets/customers`，页面内容与行为不变

### Requirement: 油站页面
`/console/assets/stations` SHALL 只提供油站的列表、新建、编辑、删除，不再有「设备分配」页签；行为与此前「油站」页签一致：
平台管理员新建必须选择客户、客户管理员不带 `customerId`；编辑总是发送当前地址；删除仍挂有设备的油站（409）提示先移走设备，并与创建/编辑的重名 409 区分。
旧地址 `/console/stations` SHALL 重定向到新地址。

#### Scenario: 油站页没有设备分配页签
- **WHEN** 管理员打开 `/console/assets/stations`
- **THEN** 页面直接显示油站列表和「新建」，没有「设备分配」页签

#### Scenario: 旧地址重定向
- **WHEN** 管理员访问 `/console/stations`
- **THEN** 被重定向到 `/console/assets/stations`

#### Scenario: 删除有设备的油站
- **WHEN** 管理员删除仍挂有设备的油站，云端返回 409
- **THEN** 页面提示「还有设备，请先移走」，不与重名冲突的提示混淆

### Requirement: 设备页合并登记、客户归属与油站归属
`/console/assets/devices` SHALL 通过 `GET /api/devices` 列出调用者可见的设备，列为：设备名、所属油站、在线状态、最近上线、最近下线、更新时间；平台管理员下另有「所属客户」列。
「所属客户」列 SHALL 只在平台管理员下显示（云端只对平台管理员返回 `customer_id`）；客户管理员 SHALL NOT 看到该列，也 SHALL NOT 看到「未知」之类的客户占位。
设备行没有 `station_id` 字段（旧版云端）SHALL 显示「未知」，SHALL NOT 当作「未分组」；`null` 显示「未分组」。

行内「分配油站」SHALL 对平台管理员与客户管理员提供（`PUT /api/devices/:device/station`，清空时发送显式 `{"stationId": null}`），候选油站只含设备所属客户的油站；
`customer_id` 为 `null`（没有客户）的设备 SHALL NOT 提供该操作。系统中 SHALL NOT 存在第二个分配设备油站的入口。
「登记设备」（工具栏）与行内「分配客户 / 转移客户」SHALL 只对 `platform_admin` 显示，行为与此前 `/ops/devices` 一致（登记规则见 `ops-device-registration`）。客户管理员 SHALL NOT 看到这两个入口。

#### Scenario: 平台管理员分配油站
- **WHEN** 平台管理员在设备页给一台属于某客户的设备选择该客户的油站并确认
- **THEN** 请求发往 `PUT /api/devices/:device/station`，成功后列表刷新，所属油站更新

#### Scenario: 清空油站
- **WHEN** 管理员把设备的油站清空
- **THEN** 请求体为 `{"stationId": null}`（键必须存在），成功后该设备显示「未分组」

#### Scenario: 没有客户的设备
- **WHEN** 平台管理员看到 `customer_id` 为 `null` 的设备
- **THEN** 该行有「分配客户」但没有「分配油站」，需先分配客户

#### Scenario: 客户管理员只能看与分配油站
- **WHEN** 客户管理员打开设备页
- **THEN** 表格没有「所属客户」列，工具栏没有「登记设备」，行内只有「分配油站」，没有「分配/转移客户」

#### Scenario: 平台管理员有完整操作
- **WHEN** 平台管理员打开设备页
- **THEN** 有「所属客户」列与「登记设备」，行内有「分配/转移客户」与（客户非空时）「分配油站」

#### Scenario: 旧版云端
- **WHEN** 设备行没有 `station_id` 字段
- **THEN** 所属油站显示「未知」，而不是「未分组」

### Requirement: 登记成功后在同页分配油站
平台管理员在设备页登记设备成功（`POST /api/devices` 返回 201 首次登记或 200 已登记）后，成功提示 SHALL 带有「分配油站」动作，点击后打开分配油站弹窗，目标为刚登记的设备与其所属客户，候选油站为该客户的油站。
用户不点击该动作时 SHALL NOT 发出任何分配请求，也 SHALL NOT 自动弹出弹窗。登记失败（400/403/409 等）SHALL NOT 出现该动作。
该客户还没有油站时，弹窗 SHALL 显示无候选油站的状态，不得发出分配请求。

#### Scenario: 登记成功后分配油站
- **WHEN** 平台管理员登记设备成功并点击提示里的「分配油站」
- **THEN** 打开分配弹窗，设备为刚登记的设备，候选为所选客户的油站，确认后发出 `PUT /api/devices/:device/station`

#### Scenario: 不点击则不分配
- **WHEN** 登记成功后用户忽略提示
- **THEN** 不发出分配请求，设备显示「未分组」

#### Scenario: 重复登记也可分配
- **WHEN** 重复登记同一客户（200，已登记）
- **THEN** 提示同样带有「分配油站」动作

#### Scenario: 失败不出现分配动作
- **WHEN** 登记返回 409（属于其他客户）
- **THEN** 只显示冲突提示，没有「分配油站」动作

#### Scenario: 客户没有油站
- **WHEN** 登记成功后点击「分配油站」，但该客户还没有油站
- **THEN** 弹窗显示无候选状态，且不能提交分配

### Requirement: 运维分区只保留平台独有的事项
`/ops` 菜单 SHALL 只包含 账号管理、配对密钥、审计日志，且仍仅对 `platform_admin` 可见；`/ops` 的默认落点 SHALL 是账号管理。
`/ops/devices` 与 `/ops/customers` SHALL 不再出现在菜单中，并重定向到 `/console/assets/devices` 与 `/console/assets/customers`。

#### Scenario: 运维菜单内容
- **WHEN** 平台管理员展开「运维管理」
- **THEN** 看到 账号管理、配对密钥、审计日志，没有设备与客户项

#### Scenario: 运维默认落点
- **WHEN** 平台管理员访问 `/ops`
- **THEN** 被重定向到账号管理

#### Scenario: 设备旧地址重定向
- **WHEN** 平台管理员访问 `/ops/devices`
- **THEN** 被重定向到 `/console/assets/devices`

#### Scenario: 客户管理员不可访问运维分区
- **WHEN** 客户管理员直接访问 `/ops/users` 等任一 `/ops/*` 地址
- **THEN** 显示 403
