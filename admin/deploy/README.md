# ECS 部署

与 yeweiyi-cloud 共用 ECS `8.154.38.176` 和宿主机 Nginx。前端是静态产物，不需要 Node 常驻或新容器。

- 当前入口：`http://8.154.38.176/user/login`（备案期间临时 HTTP 入口）。
- HTTPS 入口：`https://api.zhongchengelec.com/user/login`；外网可用性取决于备案/域名接入状态。
- 静态文件：`/var/www/yeweiyi-admin/releases/<版本>`，`current` 软链接指向当前版本。
- 同源 `/api/`、`/ws/`、`/healthz` 沿用 cloud 代理、鉴权和登录限流。
- Nginx：`/etc/nginx/conf.d/yeweiyi-api.conf`、`z-yeweiyi-api-ip.conf`；静态配置片段位于 `/etc/nginx/snippets/yeweiyi-admin-static.conf`。

## 首次接入

在上述两个配置的后端 `location /` 改成 `location ~ ^/(api(?:/|$)|ws(?:/|$)|healthz$)`，
保留原代理参数，并在同一 server 中加入 `include /etc/nginx/snippets/yeweiyi-admin-static.conf;`。
HTTPS 的 HTTP 重定向 server 不改。复制本目录 `nginx-static.conf` 到该 snippet 路径。
修改前备份配置，执行 `sudo -n nginx -t`，通过后 `sudo -n systemctl reload nginx`。

## 自动 CI/CD

工作流为 `.github/workflows/ci.yml`，使用 Node 22 和 `npm ci`，依赖版本锁定在 `package-lock.json`。

- PR：lint（含类型检查）、全量 Vitest、发布脚本回滚测试、生产构建；不读取部署 secrets，不发布。
- main push：同样检查后保存 `admin-dist` artifact；deploy 作业只发布这份产物，不重新构建。
- Actions → CI/CD → Run workflow：选择 main 可手动重跑当前版本；其他分支只检查。
- 发布前核对提交仍为 main 最新版本，旧版本跳过。工作流不取消运行中的发布，服务器用 flock 互斥。
- 文件校验和通过后原子切换 current，检查版本、登录页、全部 JS/CSS、后端健康及未登录 API 401；失败恢复旧链接。
- 线上历史版本保留，自动发布只修改 admin 的版本目录和链接，不修改 Nginx、cloud 或数据库。

Repository Actions Secrets：`DEPLOY_HOST`、`DEPLOY_USER`、`DEPLOY_PORT`（默认 22）、
`DEPLOY_SSH_KEY`、`DEPLOY_KNOWN_HOSTS`。部署使用专用密钥，服务器授权行加 `restrict`，关闭 SSH 转发及 PTY；
私钥仅保存于 admin 仓库 Secrets。用户已授权使用现有 www 用户及免密 sudo。
主机公钥从已核实的运维 SSH 连接读取，StrictHostKeyChecking 保持启用。

服务器固定路径：上传暂存 `/home/www/yeweiyi-admin/ci-*`，发布目录 `/var/www/yeweiyi-admin/releases/<SHA>-<run>-<attempt>`。
暂存目录在工作流结束时清理。需要变更服务器布局时同步修改 workflow 和 remote-deploy.sh。

## 手动发布与回滚

本机执行 `npm run lint`、`npm run test`、`npm run build`，构建当前工作区。
给 dist 加 `release.txt`（提交 SHA、构建时间），并对全部文件生成 `SHA256SUMS`。
通过与 cloud 相同的运维 SSH 通道上传 dist 到一个新版本目录；不要覆盖已有版本。
将文件权限设为目录 755、文件 644，Nginx 需要读取权限。
将 `remote-deploy.sh` 上传到服务器后执行：

```bash
sudo -n bash /home/www/yeweiyi-admin/remote-deploy.sh <版本目录名>
```

脚本通过校验和检查后原子切换 current，并检查版本标识、SPA 深链接和后端健康，失败恢复旧链接。
发布后从外网检查登录页、JS/CSS、未登录 API 返回 401；有账号时再验证真实登录。
历史版本保留，手动回滚可再次运行脚本指定旧版本。

不要把 SSH 私钥或业务凭据提交到仓库。自动发布的完整记录和失败原因在 GitHub Actions 的 CI/CD 运行中查看。
