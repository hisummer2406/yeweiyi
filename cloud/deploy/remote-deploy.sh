#!/usr/bin/env bash
# 目标机使用已有 sudo 权限；不要求把部署用户加入 docker 组。
# 发布凭据由 CI 放在临时 DOCKER_CONFIG 中，脚本退出后由 CI 清理。
set -euo pipefail
DIR="${1:?用法：$0 <部署目录> <固定镜像>}"
IMAGE="${2:?用法：$0 <部署目录> <固定镜像>}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8080/healthz}"
HEALTH_TRIES="${HEALTH_TRIES:-30}"
# GHCR digest：照常 pull。yeweiyi-local/cloud:sha-<完整提交>：CI 已在 Runner 上构建并经 SSH 载入、核对过镜像标识
# （ghcr.io 推送不通时的发布路径，见 cloud-deploy.yml image_source=runner），这里不 pull，只要求它已在本机。
if [[ "$IMAGE" =~ ^ghcr\.io/[a-z0-9._/-]+@sha256:[a-f0-9]{64}$ ]]; then
  PULL=1
elif [[ "$IMAGE" =~ ^yeweiyi-local/cloud:sha-[a-f0-9]{40}$ ]]; then
  PULL=0
else
  echo '镜像必须是 GHCR sha256 digest 或 yeweiyi-local/cloud:sha-<提交>' >&2; exit 1
fi
cd "$DIR"
[[ -f .env.prod ]] || { echo '.env.prod 不存在' >&2; exit 1; }
# 同一主机上手动运行也互斥，避免两个进程同时改环境文件。
exec 9>.deploy.lock
flock -n 9 || { echo '另一次部署正在运行' >&2; exit 1; }
docker_cmd() { sudo -n docker --config "${DOCKER_CONFIG:-$DIR/.docker}" "$@"; }
compose() {
  docker_cmd compose --env-file .env.prod -f docker-compose.yml -f docker-compose.prod.yml \
    -f docker-compose.thingsboard.yml -f docker-compose.thingsboard-tls.yml \
    -f deploy/profiles/small.yml "$@"
}
set_image() {
  local tmp
  tmp="$(mktemp .env.prod.XXXXXX)"
  chmod 600 "$tmp"
  { grep -v '^API_IMAGE=' .env.prod || true; printf 'API_IMAGE=%s\n' "$1"; } > "$tmp"
  mv "$tmp" .env.prod
}
wait_healthy() {
  local target actual running
  target="$(docker_cmd image inspect "$1" --format '{{.Id}}')" || return 1
  for _ in $(seq 1 "$HEALTH_TRIES"); do
    actual="$(docker_cmd inspect yeweiyi-api --format '{{.Image}}' 2>/dev/null || true)"
    running="$(docker_cmd inspect yeweiyi-api --format '{{.State.Running}}' 2>/dev/null || true)"
    if [[ "$actual" == "$target" && "$running" == true ]] && curl -fsS --max-time 5 "$HEALTH_URL" >/dev/null 2>&1; then return 0; fi
    sleep 2
  done
  return 1
}
PREV="$(grep '^API_IMAGE=' .env.prod | tail -n1 | cut -d= -f2- || true)"
# 拉取失败（或本机镜像不在）时旧环境与 API 完全不动。
if [[ "$PULL" == 1 ]]; then docker_cmd pull "$IMAGE"; else docker_cmd image inspect "$IMAGE" >/dev/null; fi
set_image "$IMAGE"
# 本脚本只更新 API；基础设施必须由首次安装流程先准备好。
# 显式运行新版本迁移，不复用上次退出成功的 migrate 容器。
if compose run --rm --no-deps migrate && compose up -d --no-deps api && wait_healthy "$IMAGE"; then
  printf '部署成功：%s\n' "$IMAGE"
  exit 0
fi
compose logs --tail 50 api >&2 || true
# 只回滚镜像，不执行数据库 down；迁移必须保持向后兼容。
if [[ -n "$PREV" ]]; then
  set_image "$PREV"
  if compose up -d --no-deps api && wait_healthy "$PREV"; then
    echo '发布失败，已恢复前一 API 镜像；数据库迁移未回滚' >&2
  else
    echo '发布与回滚均未通过验收，需要人工介入' >&2
  fi
else
  echo '首次发布失败，没有前一版本可恢复' >&2
fi
exit 1
