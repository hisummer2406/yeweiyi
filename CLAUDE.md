# CLAUDE.md / AGENTS.md

`AGENTS.md` 是本文件的符号链接。只编辑本文件。

安卓液位仪的单仓库。2026-11-06 由四个仓库合并而来，各自的完整提交历史都保留在对应目录下。

| 目录 | 内容 | 原仓库 |
| --- | --- | --- |
| `cloud/` | 云端后端（Go + PostgreSQL + Redis + ThingsBoard CE） | yeweiyi-cloud |
| `admin/` | 运维/客户桌面后台（Ant Design Pro） | yeweiyi-admin |
| `miniapp/` | 微信小程序 | yeweiyi-miniapp |
| `android/` | 安卓液位仪设备端 | zhongchengyeweiyi |

## 规则

- 改某个子项目前，先读该目录下的 `CLAUDE.md`；它们仍然有效，命令都在该目录内执行。
- 跨子项目的协议/接口变更可以放在同一个 PR，但仍须获得相关负责人确认（见 `cloud/CLAUDE.md`）。
- CI 在根目录 `.github/workflows/`，按目录触发：`cloud-*` 只看 `cloud/`，`admin-ci` 只看 `admin/`。
  云端发布（Cloud Deploy）须选一个改过 `cloud/` 且 Cloud CI/Image 通过的 main 提交。
- 合并前的 issue/PR 编号（如 `#85`、`zhongchengyeweiyi#39`）指原仓库，原仓库已归档但仍可查。
- 提交标题使用中文，可加目录前缀，如 `cloud: ...`。仅在用户要求时提交。
