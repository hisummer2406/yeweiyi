# yeweiyi-admin

液位仪云平台的 Web 前端：**运维/管理控制台**（`/ops/*`）与**客户 PC 监控台**（`/console/*`，
小程序的桌面版），同一个 Ant Design Pro 应用，靠路由和角色区分，不是两个仓库。

详细的角色划分、跨仓库关系、快速启动步骤见 [`CLAUDE.md`](./CLAUDE.md)——这是本仓库唯一维护的
说明文档，请不要另起一份 README 内容分叉。

## 关联仓库

- 后端：`../cloud/`（本仓库的全部 API 都来自这里）
- 微信小程序：`../miniapp/`（`/console/*` 的移动端等价物）
- Android 设备端：`../android/`

## 技术栈

Ant Design Pro（Umi Max v4 + antd v6 + ProComponents v3），从官方模板裁剪而来，删除了示例页面
与该模板自带的 CI/部署/mock 配置。
