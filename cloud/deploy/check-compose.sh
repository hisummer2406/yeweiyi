#!/usr/bin/env bash
# 校验 Compose 文件：各种组合能解析、生产叠加缺密码会报错、危险端口只绑环回。
#
# 在仓库根目录运行：  ./deploy/check-compose.sh
# 需要 docker compose（v2.24+，因为用了 !override）和 jq。CI 里每次都会跑它。
#
# 为什么要有这个：生产 Compose 曾经因为 `api:` 下全是注释而根本解析不了，端口隔离也曾被叠加文件
# 悄悄撤销（Compose 对 ports 是追加合并），两件事都是上线前人眼才发现的。这里把它们变成会变红的检查。
set -euo pipefail
cd "$(dirname "$0")/.."

command -v jq >/dev/null || { echo "需要 jq" >&2; exit 2; }

# 仅用于解析的假值，不代表任何真实凭据。
export POSTGRES_PASSWORD=check-only REDIS_PASSWORD=check-only \
       JWT_SECRET=check-only-check-only-check-only-00 TB_PASSWORD=check-only \
       API_IMAGE=ghcr.io/example/yeweiyi-cloud:sha-check

DEV=(-f docker-compose.yml -f docker-compose.thingsboard.yml)
TLS=("${DEV[@]}" -f docker-compose.thingsboard-tls.yml)
PROD=(-f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.thingsboard.yml -f docker-compose.thingsboard-tls.yml)

# 兼容 macOS 自带的 bash 3.2：不用 declare -n，按名字选择文件组合。
dc() {
  local name=$1; shift
  case "$name" in
    DEV)  docker compose "${DEV[@]}"  "$@" ;;
    TLS)  docker compose "${TLS[@]}"  "$@" ;;
    PROD) docker compose "${PROD[@]}" "$@" ;;
    *) echo "未知组合 $name" >&2; return 2 ;;
  esac
}

fail=0
ok()  { echo "ok    $*"; }
bad() { echo "FAIL  $*" >&2; fail=1; }

# 1. 每种组合都必须能解析。
for name in DEV TLS PROD; do
  if dc "$name" config -q 2>/dev/null; then ok "$name 组合可解析"; else
    bad "$name 组合无法解析："; dc "$name" config -q 2>&1 | head -5 >&2
  fi
done

# 2. 生产叠加缺任何一个必填凭据都必须报错，而不是悄悄用弱默认值起来。
for var in POSTGRES_PASSWORD REDIS_PASSWORD JWT_SECRET TB_PASSWORD API_IMAGE; do
  if env -u "$var" docker compose "${PROD[@]}" config -q 2>/dev/null; then
    bad "生产叠加缺少 $var 却能解析：弱默认值会悄悄生效"
  else
    ok "生产叠加缺少 $var 会报错"
  fi
done

# 3. 这些端口只能绑 127.0.0.1。PostgreSQL / Redis / ThingsBoard 控制台不该对网络开放；
#    明文 MQTT(1884) 在启用 TLS 的组合里同样收回环回。
guard_ports() {  # $1=组合名  $2...=要检查的端口
  local name=$1; shift
  local json; json=$(dc "$name" config --format json)
  for port in "$@"; do
    local exposed
    exposed=$(jq -r --arg p "$port" '
      [.services | to_entries[] | .key as $svc | (.value.ports // [])[]
       | select((.published | tostring) == $p)
       | select((.host_ip // "0.0.0.0") != "127.0.0.1")
       | $svc] | join(",")' <<<"$json")
    if [ -n "$exposed" ]; then bad "${name}：端口 $port 对网络开放（服务：${exposed}）"; else ok "${name}：端口 $port 只绑环回或未发布"; fi
  done
}
guard_ports DEV  5432 6379 8081
guard_ports TLS  5432 6379 8081 1884
guard_ports PROD 5432 6379 8081 1884 8080

exit "$fail"
