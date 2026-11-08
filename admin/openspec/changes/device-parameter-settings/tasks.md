## 1. 请求与纯函数
- [x] 1.1 `ApiError.data`；`services/ant-design-pro/deviceConfig.ts`（罐/整机镜像、标定表、提交、单条/按 requestId 查询、列表、开关）+ 测试
- [x] 1.2 `utils/deviceConfig.ts`：字段表、差异、预检、命令阶段、回显判定、错误归类、requestId + 测试

## 2. 页面
- [x] 2.1 设备详情加 `settings`、`commands` 页签
- [x] 2.2 参数设置：开关、罐选择、当前值、表单、确认弹窗、提交前核对油站
- [x] 2.3 命令跟踪：轮询、回显核对、超时找回、同编号重试、隐藏暂停
- [x] 2.4 整机设置（平台管理员可改）、标定表只读
- [x] 2.5 操作记录页签
- [x] 2.6 `/console/issue-command` 设备入口
- [x] 2.7 中英文文案

## 3. 验证
- [x] 3.1 组件测试：只发改动、取消不发、换机停止、超时找回、in-flight、回显三态、客户管理员只读
- [x] 3.2 `npm run tsc`、`npm run lint`、`npm run test`、`npm run build`
- [ ] 3.3 本地真实云端（cloud PR #13）浏览器联调（未做）；`CLAUDE.md` 已更新
