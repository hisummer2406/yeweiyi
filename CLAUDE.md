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
  复用（`.github/scripts/ci-reuse.sh`）：本次检出的 `cloud/`、`.github/` 目录树与某次成功运行实际测过的相同（记在 PR 头的
  提交状态「已测树/<工作流>」里），则 Cloud CI 全部作业跳过（PR 上的 Cloud Image 同理）。只改 `cloud/` 文档不触发 Cloud CI。
  仓库 2026-11-26 起公开（许可证 [LICENSE.md](LICENSE.md)：PolyForm Noncommercial，禁止商用），全部工作流跑在 GitHub
  托管的 `ubuntu-latest` 上；不要再注册自托管 Runner。Action 钉 SHA，部署作业走 `production` 环境；
  缓存、隔离与运维见 [.github/ci/README.md](.github/ci/README.md)。
- Android 设备端由用户负责开发，单测交由专门的 Android 开发同事。当前协作默认不编写、运行或重跑 Android 单测，也不将其纳入仓库的自动 CI。现有托管工作流及停用要求见 [CI 运维说明](.github/ci/README.md#android-单测交接)。
- 合并前的 issue/PR 编号（如 `#85`、`zhongchengyeweiyi#39`）指原仓库（已归档，只读可查）。合并时仍开着的已迁来，
  新编号：cloud#79→#2、cloud#93→#3、admin#38→#4、miniapp#12→#5、miniapp#26→#6、
  zhongchengyeweiyi#33→#7、#50→#8、#54→#9，PR zhongchengyeweiyi#48→#1。新 issue 用标签
  `cloud`/`admin`/`miniapp`/`android` 区分目录。
- 提交标题使用中文，可加目录前缀，如 `cloud: ...`。仅在用户要求时提交。
