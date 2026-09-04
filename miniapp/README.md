# yeweiyi-miniapp

安卓液位仪（`com.sansen.yeweiyi`，油罐监控设备）**需求B（PC 端 / 小程序远程数据监控）**的
微信小程序前端。本仓库只是客户端：不连接 MQTT，不连接 ThingsBoard，不含任何后端逻辑，全部数据
经 [`yeweiyi-cloud`](https://github.com/hisummer2406/yeweiyi-cloud) 的 REST / WebSocket API 读写。

```text
本仓库（微信小程序）
    ⇄ REST / WebSocket
yeweiyi-cloud（Go 后台）
    ⇄
ThingsBoard CE / PostgreSQL / Redis
```

> **开发者与 AI 智能体请先读 [`CLAUDE.md`](./CLAUDE.md)**——当前 API 契约、已删除端点清单、
> 架构与页面说明都在那里，且随 `yeweiyi-cloud` 的接口变化同步维护。这份 README 是给人看的
> 项目概览，`CLAUDE.md` 才是随时可能变化的、权威的现状记录。

## 项目边界

- **属于本项目**：设备列表与配对、实时数据/报警/出入库历史查看、控制命令下发与状态跟踪、
  报警实时推送。
- **不属于本项目**：云端业务逻辑、ThingsBoard 接入、设备协议（`yeweiyi-cloud` 仓库）；
  设备固件（`zhongchengyeweiyi` 仓库）；局域网 PC 监控（需求A，设备自带 HTTP 服务，无需本项目）。

## 技术栈

- **原生微信小程序**（WXML/WXSS + TypeScript，官方 `wx.*` API，不用 uni-app/Taro 等跨端框架）
- TypeScript，经 WeChat DevTools 内置编译插件转译，无打包器
- 无 UI 组件库、无状态管理库——见 `CLAUDE.md` "Tech stack" 一节的取舍理由

## 项目结构

```
miniprogram/
  app.ts / app.json         # 全局配置、tabBar、页面路由表
  config/env.ts             # 后端地址（BASE_URL / WS_BASE_URL）
  utils/
    request.ts               # wx.request 封装，注入 JWT，ApiError 携带状态码与响应体
    ws.ts                     # /ws/alarms 的重连封装
    auth.ts                   # 登录、JWT 的 sub/role 声明解码
    storage.ts                # token 本地存储
    pairing.ts                # 设备配对二维码解析（格式为占位实现，见文件内注释）
    labels.ts                 # 报警类型/严重度等封闭枚举的中文标签表，以及控制命令方法表
  types/api.d.ts             # 与 yeweiyi-cloud 手动对齐的响应类型
  pages/
    login/                    # 登录
    devices/                  # 设备列表（tabBar）、扫码绑定
    device-detail/             # 实时数据 / 报警记录 / 出入库记录 / 控制命令 四个内页 tab
    issue-command/             # 发送一条控制命令
    profile/                   # 账号信息、退出登录（tabBar）
```

## 本地开发

```bash
npm install          # 只拉取 typescript 与类型声明，不打包进小程序本体
npm run typecheck     # tsc --noEmit，本仓库目前唯一的自动化检查
```

用 WeChat DevTools 打开仓库根目录（`project.config.json` 已指向 `miniprogram/` 作为小程序根目录）。
`appid` 默认是游客态占位值，正式发布前需换成真实 AppID。

`config/env.ts` 的 `BASE_URL` / `WS_BASE_URL` 默认指向本地 `yeweiyi-cloud`
（`http://127.0.0.1:8080` / `ws://127.0.0.1:8080`），按实际后端地址修改。本地非 HTTPS 地址需要在
DevTools「详情 → 本地设置」勾选「不校验合法域名…」，否则所有请求会直接失败。

## 现状

- **代码已按 `yeweiyi-cloud` 当前的 ThingsBoard 单栈 API 重建完成**（2026-09-04），`npm run
  typecheck` 通过。旧版（对着已删除的 Alink API 写的）已整体替换，细节见
  `CLAUDE.md` 顶部的说明与 git 历史。
- **尚未在 WeChat DevTools 或真实 `yeweiyi-cloud` 前实际跑过**——这是下一步，先于任何进一步开发。
- 设备配对二维码的内容格式（`utils/pairing.ts`）是本仓库自行选定的占位实现，还没有跟设计物理
  标签的一方确认过。
- 出入库/交班记录订正、油罐与探针配置下发，云端都还没有可用的接口设计，本仓库因此也没有对应
  界面——不在这里替后端发明一个。
