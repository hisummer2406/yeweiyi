#!/usr/bin/env bash
# 备份两个数据库：业务库（Postgres 容器）与 ThingsBoard 自带的内置库。
#
# 为什么是两个：ThingsBoard 的 tb-postgres 镜像**内置了自己的 PostgreSQL**（容器内 /data/db），
# 与本项目的业务 Postgres 是两个互不相干的实例。只备份其中一个，恢复时会得到一个
# “设备在、业务数据没了”或“业务数据在、设备和遥测没了”的半套系统。
#
#   ./backup.sh [输出目录]        默认 ./backups
set -euo pipefail

# sha256 工具在两端不同名：macOS 有 shasum 无 sha256sum，精简版 Linux（debian-slim 等）
# 正好相反。这些脚本一端在打包机（多为 macOS）跑、一端在内网 Linux 跑，写死任何一个都会
# 在另一端失败——而失败的地方恰好是校验环节，最不该出问题的那一步。
sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"
  else shasum -a 256 "$@"; fi
}


OUT="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/backups}"
STAMP="$(date +%Y%m%d-%H%M%S)"
DIR="$OUT/$STAMP"
TB_CONTAINER="${TB_CONTAINER:-yeweiyi-thingsboard}"
PG_CONTAINER="${PG_CONTAINER:-yeweiyi-postgres}"
PG_USER="${PG_USER:-yeweiyi}"
PG_DB="${PG_DB:-yeweiyi}"

for c in "$TB_CONTAINER" "$PG_CONTAINER"; do
  docker inspect "$c" >/dev/null 2>&1 || { echo "!!! 容器 $c 不存在" >&2; exit 1; }
done

mkdir -p "$DIR"
echo "==> 备份到 $DIR"

# 业务库：命令、审计、用户、设备绑定。审计按 issue #1 需长期保留，是这份备份里最不可再生的部分。
echo "    业务库 ($PG_DB)"
docker exec "$PG_CONTAINER" pg_dump -U "$PG_USER" -d "$PG_DB" --clean --if-exists \
  | gzip > "$DIR/business.sql.gz"

# ThingsBoard 内置库：设备、凭据、遥测、属性、规则链。
echo "    ThingsBoard 内置库"
docker exec -u thingsboard "$TB_CONTAINER" pg_dump -U thingsboard -d thingsboard --clean --if-exists \
  | gzip > "$DIR/thingsboard.sql.gz"

# 版本与环境信息：恢复时用来确认目标环境是否匹配。跨大版本恢复 ThingsBoard 的库是不安全的。
{
  echo "backup_time=$(date -Iseconds)"
  echo "thingsboard_image=$(docker inspect "$TB_CONTAINER" --format '{{.Config.Image}}')"
  echo "business_pg_image=$(docker inspect "$PG_CONTAINER" --format '{{.Config.Image}}')"
  echo "schema_version=$(docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tAc \
      'SELECT version||CASE WHEN dirty THEN E'"'"'-dirty'"'"' ELSE E'"'"''"'"' END FROM schema_migrations' 2>/dev/null || echo unknown)"
} > "$DIR/manifest.txt"

( cd "$DIR" && sha256 ./* > SHA256SUMS 2>/dev/null || true )

echo
echo "完成："
ls -lh "$DIR" | tail -n +2 | awk '{printf "    %-20s %s\n", $9, $5}'
cat "$DIR/manifest.txt" | sed 's/^/    /'
echo
echo "注意：备份不含容器卷本身，只含两个数据库的逻辑转储。"
echo "      ThingsBoard 的日志卷与业务库的 WAL 不在其中，也不需要在其中。"
