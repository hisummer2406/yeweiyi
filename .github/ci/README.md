# CI 运行环境（yeweiyi）

仓库 2026-11-26 起公开，所有工作流都跑在 GitHub 托管的 `ubuntu-latest` 上（公开仓库免费）。
原先本机 Lima 虚拟机 `ci-runner` 上的自托管 Runner `mbp2017-yeweiyi`（标签 `yeweiyi-ci`）已停用：公开仓库挂自托管
Runner，任何人的 fork PR 都可能在本机执行代码。**不要再给本仓库注册自托管 Runner。**

## 公开仓库的安全约定

- Action 一律钉在提交 SHA 上（行尾注释标版本），由 [Dependabot](../dependabot.yml) 每月提升级 PR。
- 部署密钥（`CLOUD_DEPLOY_*`、`ADMIN_DEPLOY_*`）只给部署作业用；两个部署作业都走 `production` 环境，环境只允许 `main`。
  fork PR 拿不到任何 secret；外部贡献者的 PR 要维护者批准后才运行工作流。
- 工作流默认 `contents: read`，只在需要的作业里加 `statuses: write` / `packages: write`。
- **允许的 Action**（仓库设置，2026-11-26）：只允许 GitHub 官方（`actions/*`、`github/*`）和
  `docker/setup-buildx-action`、`docker/login-action`、`docker/metadata-action`、`docker/build-push-action`，且必须钉 SHA。
  新增其他第三方 Action 前，先在 Settings → Actions → General 把它加进白名单，否则工作流直接启动失败。
- **规则集**：`main` 禁止删除、强推，改动须走 PR（0 审批）；`cloud-v*` 标签禁止删除、改动、强推。仓库管理员可绕过。
- **扫描**：Dependabot 告警与安全更新 PR、CodeQL 默认配置（extended 规则集，PR 与每周；Android Java/Kotlin 需编译，
  默认配置跑不了已自动排除）、密钥扫描与推送拦截、漏洞私报均已开启。
  `android/app/src/main/cpp/bspatch/bizp/` 里上游 bzip2 命令行工具代码的告警已按「不可达」忽略。

## 缓存

托管 Runner 每次都是新机器：`setup-go` / `setup-node` 用 GitHub 缓存（按 `go.sum` / `package-lock.json`），
Cloud Image 用 `type=gha` 构建缓存。集成测试容器里的 Go 模块/构建缓存放本次 `$RUNNER_TEMP/ci-cache`（冷启动）。
sqlc / migrate 预编译包每次下载并校验 sha256（`.github/scripts/ci-fetch-tool.sh`）。

集成测试用 `go test -count=1`，并且日志里出现 `ok … (cached)` 或 `--- SKIP` 就判失败。
Admin 的 `admin-dist` artifact 是 check-build → deploy 之间交付同一构建产物的通道，保留。

## 隔离与清理

- **凭证**：SSH 私钥与 known_hosts 放 `$RUNNER_TEMP/ssh`，`ssh -F /dev/null -i … -o IdentitiesOnly=yes -o IdentityAgent=none`；
  最后一步 `if: always()` 删除。
- **集成测试**：每次运行一个 Compose 项目 `ywy-ci-<run_id>-<attempt>`，叠加
  `.github/ci/cloud-integration.compose.yml`。PostgreSQL / Redis / ThingsBoard 与跑 `go test` 的
  golang 容器共享一个网络命名空间，**宿主不发布任何端口**（部分测试写死 `localhost:8081`）。业务库在命名空间内改用
  55432（tb-postgres 自带的库占 5432）。这样同一套命令也能在本机复现而不抢端口。
- Cloud CI 不用 `services:` 服务容器（2026-11-23 起，原自托管 Runner 上多次卡在 Initialize containers）。
  sqlc 输出检查并入 `go` 作业（不需要数据库），迁移 up/down/up 往返并入集成测试，复用其 PostgreSQL。`go` 与 `integration` 并行。
- PR 上 Cloud CI、Cloud Image、Admin CI/CD 都在新提交到来时取消旧运行；`main` 上不取消，且 Cloud CI/Image 每个提交一个
  并发组（共用一组时排队中的运行会被后来的替换，那个提交就无法部署）。
- **复用**（`.github/scripts/ci-reuse.sh`）：全部作业成功后，把本次实际检出（PR 上是与 base 的合并结果）的 `cloud/`、`.github/`
  目录树记为被测提交的提交状态「已测树/<工作流>」。下次第一个作业比较本次检出的目录树：与同分支近期 PR 头（PR）或合并提交的
  PR 头（main）上记录的相同，则其余作业跳过。查不到或不同一律全量。Cloud Image 只在 PR 上复用，main 每次都构建推送。

## Cloud Deploy 的镜像身份校验

Runner 与服务器的 Docker 可能用不同镜像存储：containerd 存储的 `docker image inspect .Id` 是 index/manifest digest，
经典存储的 `.Id` 是 config digest。`.github/scripts/deploy-image-identity.sh` 按两端存储类型从固定 digest 推导期望值
（Runner `.Id` = 固定 digest → 导出包 `index.json` 只指向它 → 同包 config 的 digest 与内容一致 →
远端经典存储期望 config digest、containerd 存储期望固定 digest），不一致仍报「远端预载镜像标识不一致」。

## ghcr.io 推送不通时发布（`image_source=runner`）

Cloud Deploy 手动触发时选 `image_source=runner`：在 Runner 上按 `release_sha` 构建镜像（无缓存），
`docker save` → SSH → 远端 `docker load`，不经 ghcr.io；不要求该提交的 Cloud Image 成功，Cloud CI 仍必须成功。
镜像名 `yeweiyi-local/cloud:sha-<完整提交>-r<运行编号>-<重试次数>`（每次运行唯一，同一提交重发不会改写服务器上已有标签），
身份以本机 `.Id` 推导并核对远端载入结果；`remote-deploy.sh` 对这种引用不 pull，只要求镜像已在远端，所选提交必须已包含
支持该格式的 `remote-deploy.sh`。

回滚：`remote-deploy.sh` 探活失败时恢复 `.env.prod` 里的上一版镜像引用，前提是该镜像仍在服务器上，且仍是切换前在运行的
镜像（不是则不自动回滚，提示人工介入）。服务器不清理旧镜像（2026-11-24 核对：无清理任务，每版约 35MB，根分区剩余 71G）；
以后若加清理，须保留 `.env.prod` 当前与上一版的镜像。

## 运维

```bash
gh run list -L 10
gh api repos/hisummer2406/yeweiyi/actions/runners --jq '.total_count'   # 应为 0：不挂自托管 Runner
```

## Android 单测交接

- 2026-11-26 确认，用户负责 Android 设备端开发，专门的 Android 开发同事负责单测。单测在同事的环境执行，仓库应停止自动触发 Android 单测。
- 现状（2026-11-26）：[`android-ci.yml`](../workflows/android-ci.yml) 已去掉 PR 和 `main` 推送触发，只保留 `workflow_dispatch` 手动入口（`ubuntu-latest`）。现有单测源文件、Gradle 测试入口和共享协议 fixture 保留，供同事验证。停用前的历史红灯（如 PR #76 上因预算未启动的 `unit-test`）按原运行记录保留，不代表应用测试失败。
- `cloud/fixtures/protocol/v1/MANIFEST.json` 的 `enforcement.crossRepoGate` 相应改为 `false`，Android 一侧标为人工校验；Cloud CI 仍自动校验 fixture。
