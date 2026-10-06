#!/usr/bin/env bash
# 在**目标服务器**上运行（由 .github/workflows/deploy.yml 经 SSH 调用）：切换 api 镜像并等它健康。
#
#   remote-deploy.sh <部署目录> <镜像，如 ghcr.io/hisummer2406/yeweiyi-cloud:sha-1234567>
#
# 前提：部署目录里已有 .env.prod（密钥只存在服务器上，不经过 CI），且服务器已 docker login ghcr.io。
# 本脚本只改 .env.prod 里的 API_IMAGE 一行。macOS 自带 bash 3.2 也要能跑：变量后紧跟中文必须写 ${var}。
set -euo pipefail

DIR="${1:?用法：$0 <部署目录> <镜像>}"
IMAGE="${2:?用法：$0 <部署目录> <镜像>}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8080/healthz}"
HEALTH_TRIES="${HEALTH_TRIES:-30}"

# latest 会让“回滚到上一个版本”失去意义，也让两次部署之间无法区分，一律拒绝。
if [[ "${IMAGE}" == *:latest || "${IMAGE}" != *:* ]]; then
  echo "!!! 镜像必须固定到具体 tag：${IMAGE}" >&2; exit 1
fi

cd "${DIR}"
[[ -f .env.prod ]] || { echo "!!! ${DIR}/.env.prod 不存在；密钥需先手工放到服务器上" >&2; exit 1; }

compose() {
  docker compose --env-file .env.prod \
    -f docker-compose.yml -f docker-compose.prod.yml \
    -f docker-compose.thingsboard.yml -f docker-compose.thingsboard-tls.yml "$@"
}

set_image() {
  # 先写临时文件再 mv，避免中途失败留下半截的 .env.prod；保持原权限（通常 600）。
  tmp="$(mktemp .env.prod.XXXXXX)"
  chmod --reference=.env.prod "${tmp}" 2>/dev/null || chmod 600 "${tmp}"
  { grep -v '^API_IMAGE=' .env.prod || true; echo "API_IMAGE=$1"; } > "${tmp}"
  mv "${tmp}" .env.prod
}

wait_healthy() {
  for _ in $(seq 1 "${HEALTH_TRIES}"); do
    if curl -fsS "${HEALTH_URL}" >/dev/null 2>&1; then return 0; fi
    sleep 2
  done
  return 1
}

PREV="$(grep '^API_IMAGE=' .env.prod | tail -n1 | cut -d= -f2- || true)"
echo "==> 当前镜像：${PREV:-（无）}"
echo "==> 目标镜像：${IMAGE}"

set_image "${IMAGE}"
compose pull api
# up 会先跑 migrate（api 依赖它成功退出）。迁移不会随回滚撤销：回滚的只是 api 镜像，
# 所以迁移必须向后兼容上一个版本——这是迁移评审要守的规则，不是本脚本能保证的。
compose up -d api

if wait_healthy; then
  echo "==> 部署成功：${IMAGE}"
  exit 0
fi

echo "!!! ${IMAGE} 在 $((HEALTH_TRIES * 2)) 秒内未通过健康检查，最近日志：" >&2
compose logs --tail 50 api >&2 || true
if [[ -n "${PREV}" ]]; then
  echo "==> 回滚到 ${PREV}" >&2
  set_image "${PREV}"
  compose up -d api
  if wait_healthy; then echo "==> 已回滚，服务恢复" >&2; else echo "!!! 回滚后仍不健康，需要人工介入" >&2; fi
else
  echo "!!! 没有上一个版本可回滚" >&2
fi
exit 1
