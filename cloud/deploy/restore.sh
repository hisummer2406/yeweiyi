#!/usr/bin/env bash
# 从 backup.sh 的产出恢复。**会覆盖目标环境的现有数据。**
#
#   ./restore.sh backups/20260902-003000
set -euo pipefail

DIR="${1:-}"
[[ -n "$DIR" && -d "$DIR" ]] || { echo "用法：$0 <备份目录>" >&2; exit 1; }
TB_CONTAINER="${TB_CONTAINER:-yeweiyi-thingsboard}"
PG_CONTAINER="${PG_CONTAINER:-yeweiyi-postgres}"
PG_USER="${PG_USER:-yeweiyi}"
PG_DB="${PG_DB:-yeweiyi}"

echo "==> 校验备份完整性"
( cd "$DIR" && shasum -a 256 -c SHA256SUMS --quiet ) || { echo "!!! 备份文件校验失败，拒绝恢复。" >&2; exit 1; }

echo "==> 备份信息"; sed 's/^/    /' "$DIR/manifest.txt"

# 跨 ThingsBoard 大版本恢复其内置库是不安全的：它的 schema 随版本迁移，旧转储灌进新实例
# 可能在很久以后才以“某个功能不工作”的形式暴露出来。这里只做提示而非硬拦，因为升级恢复
# 是合法场景——但必须是人主动确认过的。
want="$(grep '^thingsboard_image=' "$DIR/manifest.txt" | cut -d= -f2-)"
have="$(docker inspect "$TB_CONTAINER" --format '{{.Config.Image}}' 2>/dev/null || echo '')"
if [[ -n "$have" && "$want" != "$have" ]]; then
  echo
  echo "!!! ThingsBoard 版本不一致：备份来自 $want，当前为 $have"
  echo "    跨大版本恢复其内置库可能导致 schema 不匹配。确认要继续请输入 yes。"
  read -r -p "    继续？[yes/N] " reply
  [[ "$reply" == "yes" ]] || { echo "已取消。"; exit 1; }
fi

echo
read -r -p "将覆盖 $PG_CONTAINER 与 $TB_CONTAINER 的现有数据。输入 yes 继续：" reply
[[ "$reply" == "yes" ]] || { echo "已取消。"; exit 1; }

echo "==> 恢复业务库"
gzip -dc "$DIR/business.sql.gz" | docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -q

echo "==> 恢复 ThingsBoard 内置库"
# ThingsBoard 必须先停：它持有连接并缓存实体，边写库边跑会得到一个自相矛盾的状态。
docker stop "$TB_CONTAINER" >/dev/null
docker start "$TB_CONTAINER" >/dev/null
echo "    等待其内置 PostgreSQL 就绪"
for _ in $(seq 1 60); do
  docker exec -u thingsboard "$TB_CONTAINER" pg_isready -U thingsboard -d thingsboard >/dev/null 2>&1 && break
  sleep 2
done
gzip -dc "$DIR/thingsboard.sql.gz" | docker exec -i -u thingsboard "$TB_CONTAINER" psql -U thingsboard -d thingsboard -q
echo "    重启 ThingsBoard 以丢弃恢复前的缓存"
docker restart "$TB_CONTAINER" >/dev/null

echo
echo "恢复完成。请运行冒烟测试确认："
echo "    ./bin/smoke -api http://localhost:8080"
