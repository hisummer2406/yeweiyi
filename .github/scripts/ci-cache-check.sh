#!/usr/bin/env bash
# 用法：ci-cache-check.sh go|npm [before|after]
#
# 自托管 Runner 的依赖/构建缓存放在本机持久目录（$YEWEIYI_CI_CACHE，Runner .env 配置），
# 不上传 GitHub 缓存。这里打印实际生效的路径和大小，并在路径不在持久目录下时直接失败——
# 某个 action 悄悄改了环境变量（如 GOCACHE、npm cache）时，缓存会退化成每次冷启动且无人察觉。
# "before" 阶段额外做一次离线命中检查，作为跨运行复用的证据（首次运行未命中是正常的）。
set -euo pipefail

root="${YEWEIYI_CI_CACHE:?YEWEIYI_CI_CACHE 未设置（应在 Runner 的 .env 中配置）}"
kind="$1" phase="${2:-before}"

check() {
  local name="$1" path="$2"
  case "$path" in
    "$root"/*) ;;
    *) echo "::error::$name=$path 不在本地持久缓存目录 $root 下" >&2; exit 1 ;;
  esac
  printf '%-10s %-45s %s\n' "$name" "$path" "$(du -sh "$path" 2>/dev/null | cut -f1)"
}

case "$kind" in
  go)
    check GOMODCACHE "$(go env GOMODCACHE)"
    check GOCACHE "$(go env GOCACHE)"
    if [[ "$phase" == before ]]; then
      # GOPROXY=off：不允许联网，只有模块已在本地缓存时才会成功。
      if GOPROXY=off GOFLAGS=-mod=mod go mod download >/dev/null 2>&1; then
        echo "依赖缓存：命中（离线可解析全部模块）"
      else
        echo "依赖缓存：未命中（首次运行或依赖有变化，本次会联网下载）"
      fi
    fi
    ;;
  npm)
    check npm_cache "$(npm config get cache)"
    if [[ "$phase" == before ]]; then
      echo "npm 缓存条目数：$(find "$(npm config get cache)/_cacache/index-v5" -type f 2>/dev/null | wc -l)"
    fi
    ;;
  *) echo "未知类型 $kind" >&2; exit 2 ;;
esac
