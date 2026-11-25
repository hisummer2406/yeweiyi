# 自托管 Runner（yeweiyi）

Cloud CI、Cloud Image、Cloud Deploy、Admin CI/CD 跑在本机（MacBook Pro 2017，4 核 / 8 GB）的
Lima 虚拟机 `ci-runner` 上（Ubuntu 24.04，x86_64，3 vCPU / 5 GiB / 60 GiB，Docker 29 + Compose v5 + Buildx）。
同一台虚拟机还跑着 KeywordIQ 的 Runner；两者**互不复用**：

| | yeweiyi | KeywordIQ（不要动） |
| --- | --- | --- |
| 注册范围 | 仓库级 `hisummer2406/yeweiyi` | 仓库级 `hisummer2406/KeywordIQ` |
| Runner 名 / 标签 | `mbp2017-yeweiyi` / `yeweiyi-ci` | `mbp2017-ci` / `mbp-ci` |
| 系统用户 | `yeweiyi-ci`（仅 `docker` 组，无 sudo） | `summer` |
| Runner 目录 | `/home/yeweiyi-ci/actions-runner`（工作目录 `_work`） | `/home/summer.guest/actions-runner` |
| systemd 服务 | `actions.runner.hisummer2406-yeweiyi.mbp2017-yeweiyi.service` | `actions.runner.hisummer2406-KeywordIQ.mbp2017-ci.service` |
| 本地缓存 | `/home/yeweiyi-ci/ci-cache` | Runner `.env` 中的 uv / pnpm 目录 |

上述工作流使用 `runs-on: [self-hosted, linux, x64, yeweiyi-ci]`。设备端单测的协作分工与当前工作流状态见文末。

## 缓存：本机持久目录，不上传 GitHub

Runner `.env`（`/home/yeweiyi-ci/actions-runner/.env`）固定以下变量，各 job 用
`.github/scripts/ci-cache-check.sh` 打印实际路径，并在路径不在 `$YEWEIYI_CI_CACHE` 下时失败：

| 用途 | 目录 |
| --- | --- |
| Go 模块 / 构建缓存 | `ci-cache/gomod`、`ci-cache/gobuild`（集成测试挂载进测试容器） |
| npm 缓存（admin 用 npm，不是 pnpm） | `ci-cache/npm` |
| Docker 构建缓存（`type=local`） | `ci-cache/buildx/cloud-image`（每次写新目录再替换，避免无限增长） |
| sqlc / migrate 预编译包 | `ci-cache/tools`（每次重新校验 sha256） |

集成测试用 `go test -count=1`，并且日志里出现 `ok … (cached)` 就判失败：GOCACHE 持久后，不禁用测试结果缓存会
让依赖 ThingsBoard/PostgreSQL 的测试直接复用上一次运行的结果。

`setup-go` 设 `cache: false`，`setup-node` 不设 `cache`，Cloud Image 不再用 `type=gha`。
Admin 的 `admin-dist` artifact 是 check-build → deploy 之间交付同一构建产物的通道，保留。

Docker 镜像（postgres、redis、tb-postgres、golang）留在守护进程里复用。注意 KeywordIQ 装了一个
每周日 04:00 的 `ci-docker-prune.timer`（`docker system/builder prune -af --filter until=168h`），
它会清掉 7 天未用的镜像；上面的目录缓存不受影响，镜像被清后会重新拉取。

## 隔离与清理

- **凭证**：镜像仓库登录用 `DOCKER_CONFIG=$RUNNER_TEMP/docker-config`；SSH 私钥与 known_hosts 放
  `$RUNNER_TEMP/ssh`，`ssh -F /dev/null -i … -o IdentitiesOnly=yes -o IdentityAgent=none`；
  最后一步 `if: always()` 删除。不写 Runner 用户的 `~/.docker`、`~/.ssh`。
- **集成测试**：每次运行一个 Compose 项目 `ywy-ci-<run_id>-<attempt>`，叠加
  `.github/ci/cloud-integration.compose.yml`。PostgreSQL / Redis / ThingsBoard 与跑 `go test` 的
  golang 容器共享一个网络命名空间，**宿主不发布任何端口**（KeywordIQ 的 CI 占宿主 5432；
  部分测试写死 `localhost:8081`）。业务库在命名空间内改用 55432（tb-postgres 自带的库占 5432）。
  收尾 `docker compose -p … down -v --remove-orphans`，并核对该项目的容器/网络/卷为 0。不做全局 prune。
- Cloud CI 不再用 `services:` 服务容器（2026-11-23 起）：它在本 Runner 上多次卡在 Initialize containers 直到超时。
  sqlc 输出检查并入 `go` 作业（不需要数据库），迁移 up/down/up 往返并入集成测试，复用其 PostgreSQL。
- PR 上 Cloud CI、Cloud Image、Admin CI/CD 都在新提交到来时取消旧运行；`main` 上不取消，且 Cloud CI/Image 每个提交一个
  并发组（共用一组时排队中的运行会被后来的替换，那个提交就无法部署）。
- **复用**（`.github/scripts/ci-reuse.sh`）：全部作业成功后，把本次实际检出（PR 上是与 base 的合并结果）的 `cloud/`、`.github/`
  目录树记为被测提交的提交状态「已测树/<工作流>」。下次第一个作业比较本次检出的目录树：与同分支近期 PR 头（PR）或合并提交的
  PR 头（main）上记录的相同，则其余作业跳过。查不到或不同一律全量。Cloud Image 只在 PR 上复用，main 每次都构建推送。
- **资源**：集成测试启动前等待同机其他 Runner 的 job 结束且可用内存 ≥ 3000 MiB（最多 20 分钟，等不到失败）；
  ThingsBoard 容器 `mem_limit: 3g`。单个 Runner 同一时间只跑一个 job。

## Cloud Deploy 的镜像身份校验

本机 Docker 29 用 containerd 镜像存储，`docker image inspect .Id` 是 index/manifest digest；
经典存储（托管 ubuntu-24.04、此前的生产服务器）的 `.Id` 是 config digest。
`.github/scripts/deploy-image-identity.sh` 按两端存储类型从固定 digest 推导期望值
（本机 `.Id` = 固定 digest → 导出包 `index.json` 只指向它 → 同包 config 的 digest 与内容一致 →
远端经典存储期望 config digest、containerd 存储期望固定 digest），不一致仍报「远端预载镜像标识不一致」。

## ghcr.io 推送不通时发布（`image_source=runner`）

Cloud Deploy 手动触发时选 `image_source=runner`：在 Runner 上按 `release_sha` 构建镜像（只读复用本机构建缓存），
`docker save` → SSH → 远端 `docker load`，不经 ghcr.io；不要求该提交的 Cloud Image 成功，Cloud CI 仍必须成功。
镜像名 `yeweiyi-local/cloud:sha-<完整提交>-r<运行编号>-<重试次数>`（每次运行唯一，同一提交重发不会改写服务器上已有标签），
身份以本机 `.Id` 推导并核对远端载入结果；`remote-deploy.sh` 对这种引用不 pull，只要求镜像已在远端，所选提交必须已包含
支持该格式的 `remote-deploy.sh`。

回滚：`remote-deploy.sh` 探活失败时恢复 `.env.prod` 里的上一版镜像引用，前提是该镜像仍在服务器上，且仍是切换前在运行的
镜像（不是则不自动回滚，提示人工介入）。服务器不清理旧镜像（2026-11-24 核对：无清理任务，每版约 35MB，根分区剩余 71G）；
以后若加清理，须保留 `.env.prod` 当前与上一版的镜像。

## 运维

```bash
limactl shell ci-runner -- systemctl status actions.runner.hisummer2406-yeweiyi.mbp2017-yeweiyi.service
limactl shell ci-runner -- sudo -u yeweiyi-ci du -sh /home/yeweiyi-ci/ci-cache/*
gh api repos/hisummer2406/yeweiyi/actions/runners --jq '.runners[]|[.name,.status,.busy]'
```

重新注册（只动 yeweiyi，不要碰 KeywordIQ 的目录和服务）：

```bash
TOKEN=$(gh api -X POST repos/hisummer2406/yeweiyi/actions/runners/registration-token --jq .token)
limactl shell ci-runner -- sudo bash -c 'cd /home/yeweiyi-ci/actions-runner && ./svc.sh stop && ./svc.sh uninstall'
# 然后以 yeweiyi-ci 用户执行 ./config.sh remove / ./config.sh --unattended --url … --token "$TOKEN" \
#   --name mbp2017-yeweiyi --labels yeweiyi-ci --work _work，再 sudo ./svc.sh install yeweiyi-ci && sudo ./svc.sh start
```

## Android 单测交接

- 2026-11-26 确认，用户负责 Android 设备端开发，专门的 Android 开发同事负责单测。单测在同事的环境执行，仓库应停止自动触发 Android 单测，Lima Runner 不接管该作业。
- 现状（2026-11-26）：[`android-ci.yml`](../workflows/android-ci.yml) 已去掉 PR 和 `main` 推送触发，只保留 `workflow_dispatch` 手动入口（仍是 `ubuntu-latest`，受 Actions 预算限制）。现有单测源文件、Gradle 测试入口和共享协议 fixture 保留，供同事验证。停用前的历史红灯（如 PR #76 上因预算未启动的 `unit-test`）按原运行记录保留，不代表应用测试失败。
- `cloud/fixtures/protocol/v1/MANIFEST.json` 的 `enforcement.crossRepoGate` 相应改为 `false`，Android 一侧标为人工校验；Cloud CI 仍自动校验 fixture。
