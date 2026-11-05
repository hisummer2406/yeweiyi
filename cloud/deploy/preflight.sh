#!/usr/bin/env bash
# 安装前检查：端口、资源、Docker。只读，不改动任何东西。
set -uo pipefail

fail=0; warn=0
ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$1"; fail=1; }
note() { printf '  \033[33m!\033[0m %s\n' "$1"; warn=1; }

echo "==> Docker"
if command -v docker >/dev/null 2>&1; then
  ok "docker $(docker version --format '{{.Server.Version}}' 2>/dev/null || echo '(守护进程未运行)')"
  docker info >/dev/null 2>&1 || bad "Docker 守护进程未运行"
else
  bad "未安装 docker"
fi
docker compose version >/dev/null 2>&1 && ok "docker compose $(docker compose version --short 2>/dev/null)" || bad "缺少 docker compose 插件"

echo "==> 端口占用"
# ThingsBoard 8081（控制台）、8883（设备 MQTT/TLS）、1884（明文，启用 TLS 后仅绑环回）、
# 业务后台 8080、Postgres 5432、Redis 6379。
for p in 8080 8081 8883 1884 5432 6379; do
  if command -v ss >/dev/null 2>&1; then busy=$(ss -ltn "sport = :$p" 2>/dev/null | grep -c LISTEN)
  else busy=$(netstat -an 2>/dev/null | grep -cE "[.:]$p .*LISTEN"); fi
  [[ "${busy:-0}" -eq 0 ]] && ok "端口 $p 空闲" || note "端口 $p 已被占用（若为本系统自身则正常）"
done

echo "==> 资源"
# ThingsBoard 官方单节点最低 4 GB（PostgreSQL 后端）；本项目还要跑 Postgres、Redis 和 Go 后台。
if [[ "$(uname)" == "Darwin" ]]; then
  mem_gb=$(( $(sysctl -n hw.memsize) / 1024 / 1024 / 1024 )); cpus=$(sysctl -n hw.ncpu)
else
  mem_gb=$(( $(awk '/MemTotal/{print $2}' /proc/meminfo) / 1024 / 1024 )); cpus=$(nproc)
fi
[[ $mem_gb -ge 8 ]] && ok "内存 ${mem_gb} GB" || { [[ $mem_gb -ge 6 ]] && note "内存 ${mem_gb} GB（建议 ≥8 GB）" || bad "内存 ${mem_gb} GB，低于最低要求 6 GB"; }
[[ $cpus -ge 4 ]] && ok "CPU ${cpus} 核" || note "CPU ${cpus} 核（建议 ≥4）"

avail=$(df -Pg . 2>/dev/null | awk 'NR==2{print $4}' || df -P . | awk 'NR==2{print int($4/1048576)}')
[[ ${avail:-0} -ge 50 ]] && ok "可用磁盘 ${avail} GB" || note "可用磁盘 ${avail} GB（建议 ≥50 GB，遥测按保留期增长）"

echo
if [[ $fail -ne 0 ]]; then echo "预检未通过，请先解决上面标 ✗ 的项。"; exit 1; fi
[[ $warn -ne 0 ]] && echo "预检通过，但有需要留意的项（!）。" || echo "预检全部通过。"
