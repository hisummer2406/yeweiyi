## 1. 出厂编号规范化

- [x] 1.1 先写 `src/utils/deviceCode.test.ts`：完整编号（含小写输入）、短码保留前导零并标记需确认、`000001`/`000002` 得到不同结果、空/含空白/含 `/ ? # \`/其他格式判无效
- [x] 1.2 实现 `src/utils/deviceCode.ts`：返回 `{status:'full'|'needsConfirm'|'invalid', code, reason?}`，默认前缀 `ZCT` 为具名常量并注释「各型号规则待产品确认」

## 2. 服务层与类型

- [x] 2.1 在 `typings.d.ts` 补 `API.DeviceRow`（snake_case 五字段）、`API.Customer`、`API.RegisterDeviceParams`、`API.RegisterDeviceResult`，字段与 cloud 真实响应一致
- [x] 2.2 新增 `devices.ts`：`listDevices()`（取 `rows`）与 `registerDevice({deviceName,customerId})`（201→`created`，200→`exists`，其他状态抛出带 `status` 的错误，`skipErrorHandler: true`）
- [x] 2.3 新增 `customers.ts`：`listCustomers()`（取 `customers`）与 `createCustomer({name})`
- [x] 2.4 `index.ts` 导出新模块；确认登记路径只在 `devices.ts` 出现，代码中不引用 pairing-secret 接口
- [x] 2.5 写服务层测试（mock `request`）：响应转换、201/200 区分、409/400/403 错误携带 `status` 与 `error`
- [x] 2.6 验证 `skipErrorHandler` 下 401 仍会清 token 回登录页；若否，在调用处转交 `handleRequestError` 并补测试

## 3. 错误归类

- [x] 3.1 写 `describeRegisterError` 测试：409 冲突、400 透传后端 `error`、403 无权限、未知状态的兜底文案
- [x] 3.2 实现 `describeRegisterError(status, error)` 纯函数及客户创建的 409 名称冲突映射

## 4. 页面

- [x] 4.1 `src/pages/ops/devices/index.tsx`：`ProTable` 显示 `GET /api/devices`，含加载、空状态、错误反馈，时间本地化，`search={false}`
- [x] 4.2 登记表单（antd `Modal` + `Form`，便于控制防重与短码确认）：编号输入 + 规范化提示（短码显示候选完整编号与确认勾选）、客户必填下拉
- [x] 4.3 客户下拉的「新建客户」内联创建：成功后刷新并自动选中，保留已填编号；409 提示名称已存在
- [x] 4.4 提交流程：`useRef` 防重 + loading；成功（created/exists）分别提示，刷新列表；失败按 3.2 提示且表单保持打开
- [x] 4.5 成功提示包含所选客户与「后台将自动采集和回补，无需等待扫码」，不显示落库进度
- [x] 4.6 i18n：`zh-CN`/`en-US` 的 `pages.ts` 增加文案，`menu.ops.devices` 改为「设备管理」
- [x] 4.7 页面交互测试：未选客户不发请求、短码未确认不发请求、连点只发一次、内联创建后选中并保留编号、409 保持表单打开；无法在 vitest 内稳定验证的项在交付说明中如实标注

## 5. 文档与验证

- [x] 5.1 更新 `CLAUDE.md`：项目状态（设备登记已接）、`src/services/` 说明、目录说明；保留符号链接 `AGENTS.md`
- [x] 5.2 依次执行并如实报告 `npm run tsc`、`npm run lint`、`npm run test`、`npm run build`
- [x] 5.3 在交付说明中写明：未联调；联调需含 cloud #49 的 `main`；列表无客户列；小程序扫码绑定及授权规则仍是待确认依赖，不宣称扫码闭环完成
