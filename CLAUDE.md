# CLAUDE.md / AGENTS.md

`AGENTS.md` 是本文件的符号链接。只编辑本文件。

安卓液位仪的单仓库。2026-11-06 由四个仓库合并而来，各自的完整提交历史都保留在对应目录下。

当前 Codex 项目为“中成电气”。旧“中成前端”“中成后端”项目已停用，后续工作统一在本项目继续。
历史决策和记忆迁移见 [项目记忆](PROJECT_MEMORY.md)；历史状态不得替代当前代码、契约和实际验收记录。

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
  复用（`.github/scripts/ci-reuse.sh`）：main 上的合并提交与 PR 头同一棵树且该 PR 的 Cloud CI 已通过，或 PR 新推送的
  `cloud/`、`.github/` 及 base 都与上次成功时相同，则 Cloud CI 全部作业跳过（PR 上的 Cloud Image 同理）。只改 `cloud/` 文档不触发 Cloud CI。
  Cloud CI/Image/Deploy 与 Admin CI/CD 跑在本机 Lima 虚拟机上的自托管 Runner（标签 `yeweiyi-ci`，与 KeywordIQ 的 Runner
  同机但独立），缓存放本机持久目录、不上传 GitHub；目录、隔离与运维见 [.github/ci/README.md](.github/ci/README.md)。
  Android CI 仍用托管 Runner。
- 合并前的 issue/PR 编号（如 `#85`、`zhongchengyeweiyi#39`）指原仓库（已归档，只读可查）。合并时仍开着的已迁来，
  新编号：cloud#79→#2、cloud#93→#3、admin#38→#4、miniapp#12→#5、miniapp#26→#6、
  zhongchengyeweiyi#33→#7、#50→#8、#54→#9，PR zhongchengyeweiyi#48→#1。新 issue 用标签
  `cloud`/`admin`/`miniapp`/`android` 区分目录。
- 提交标题使用中文，可加目录前缀，如 `cloud: ...`。仅在用户要求时提交。
