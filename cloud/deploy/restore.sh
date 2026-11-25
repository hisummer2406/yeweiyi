#!/usr/bin/env bash
# 从 backup.sh 的产出恢复。**会覆盖目标环境的现有数据。**
#
#   ./restore.sh backups/20260902-003000
set -euo pipefail

# sha256 工具在两端不同名：macOS 有 shasum 无 sha256sum，精简版 Linux（debian-slim 等）
# 正好相反。这些脚本一端在打包机（多为 macOS）跑、一端在内网 Linux 跑，写死任何一个都会
# 在另一端失败——而失败的地方恰好是校验环节，最不该出问题的那一步。
sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"
  else shasum -a 256 "$@"; fi
}


DIR="${1:-}"
[[ -n "$DIR" && -d "$DIR" ]] || { echo "用法：$0 <备份目录>" >&2; exit 1; }
TB_CONTAINER="${TB_CONTAINER:-yeweiyi-thingsboard}"
PG_CONTAINER="${PG_CONTAINER:-yeweiyi-postgres}"
PG_USER="${PG_USER:-yeweiyi}"
PG_DB="${PG_DB:-yeweiyi}"
API_URL="${API_URL:-http://localhost:8080}"

# 任何命令在不属于 &&/||/if 等豁免场景下失败时给出提示，而不是让操作者对着一堆 docker/psql
# 报错自己判断现在系统处于什么状态。PR #11 复核指出：失败路径也应说明系统仍是停止的，而不是
# 只报错走人。这不影响下面单独注册的 EXIT trap（临时恢复容器的清理）——两者互不覆盖，
# EXIT trap 总会在此 trap 之后照常执行。
on_error() {
  local ec=$?
  echo >&2
  echo "!!! 恢复未完成（退出码 ${ec}）。" >&2
  echo "    ThingsBoard 容器此时应仍是停止状态（本脚本没有在失败路径上启动它）。" >&2
  echo "    业务库与 ThingsBoard 内置库这次是否都已恢复完成是不确定的——先查清上面报错的" >&2
  echo "    原因，不要在没搞清楚之前手动 docker start ${TB_CONTAINER}，以免在不一致的数据上" >&2
  echo "    冷启动。确认业务后台仍处于停止状态，修好问题后重新执行：" >&2
  echo "      ./restore.sh $DIR" >&2
}
trap on_error ERR

echo "==> 校验备份完整性"
( cd "$DIR" && sha256 -c SHA256SUMS --quiet ) || { echo "!!! 备份文件校验失败，拒绝恢复。" >&2; exit 1; }

echo "==> 备份信息"; sed 's/^/    /' "$DIR/manifest.txt"

echo "==> 确认业务后台已停止"
# 之前这里完全没有检查——恢复脚本直接把 --clean 的转储灌进正在运行的业务 PostgreSQL，
# 而业务后台（Go 进程，不受本脚本管理，可能是前台 &、systemd 服务或别的什么）仍可能持有连接
# 池并继续查询/写入正被 DROP/CREATE 的表；就算撑过去了，恢复出的库也可能混入备份时间点之后
# 才发生的新写入，与随后恢复的 ThingsBoard 快照不再对应同一个时间点。PR #11 复核发现这一点，
# 而上一轮修复只处理了 ThingsBoard 自己那一侧（不让它的 Java 应用跟着启动）——业务后台是完全
# 独立的另一个写入者，必须由操作者显式停掉，本脚本管不到它怎么启动的，只能探测它是否还在响应。
if curl -sf --max-time 3 "$API_URL/healthz" >/dev/null 2>&1; then
  echo "!!! 业务后台仍在响应（$API_URL/healthz）。" >&2
  echo "    恢复期间它可能继续查询或写入正在被清空重建的表，恢复结果也可能混入" >&2
  echo "    备份时间点之后的新写入。请先停止它（systemd 部署：systemctl stop <unit>；" >&2
  echo "    前台或 & 方式启动：kill 对应进程），确认 $API_URL/healthz 不再响应后重跑本脚本。" >&2
  echo "    若业务后台监听在其他地址，用 API_URL=<地址> ./restore.sh ... 指定后重新检查。" >&2
  exit 1
fi
echo "    未检测到响应，视为已停止"

# 跨 ThingsBoard 大版本恢复其内置库是不安全的：它的 schema 随版本迁移，旧转储灌进新实例
# 可能在很久以后才以“某个功能不工作”的形式暴露出来。这里只做提示而非硬拦，因为升级恢复
# 是合法场景——但必须是人主动确认过的。
want="$(grep '^thingsboard_image=' "$DIR/manifest.txt" | cut -d= -f2-)"
have="$(docker inspect "$TB_CONTAINER" --format '{{.Config.Image}}' 2>/dev/null || echo '')"
if [[ -n "$have" && "$want" != "$have" ]]; then
  echo
  echo "!!! ThingsBoard 版本不一致：备份来自 ${want}，当前为 $have"
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

# 之前这里在停容器后紧接着 `docker start` 整个容器，再等内置 PostgreSQL 就绪就直接灌库——但
# 启动这个容器等于同时启动了 ThingsBoard 应用（两者由镜像自带的 start-tb.sh 一起拉起，是同一个
# 进程树，不是两个可以分别控制的服务），应用可能在 psql 跑之前就已经连接、缓存甚至写库了，正是
# 上面那句注释说"必须先停"要避免的状态，只是被这几行自己違反了。PR #11 复核发现。
#
# 修法：恢复期间只启动内置 PostgreSQL，不启动应用——用一个临时容器，挂同一份数据卷
# （--volumes-from 对已停止的容器同样有效），但把入口覆盖成只跑 start-tb.sh 在启动 Java 之前
# 会先调用的 start-db.sh，然后原地等待，这样 Java 进程根本不会被起来。真正的 ThingsBoard 容器
# 恢复完成、临时容器被清理之后才启动，此时是在已恢复好的数据上冷启动，不需要再多一次重启来丢弃
# 恢复前缓存的状态——那个状态这次压根没产生过。
TB_IMAGE="$(docker inspect "$TB_CONTAINER" --format '{{.Config.Image}}')"
RESTORE_CONTAINER="${TB_CONTAINER}-restore-db"
docker rm -f "$RESTORE_CONTAINER" >/dev/null 2>&1 || true

cleanup_restore_container() {
  docker exec -u thingsboard "$RESTORE_CONTAINER" stop-db.sh >/dev/null 2>&1 || true
  docker rm -f "$RESTORE_CONTAINER" >/dev/null 2>&1 || true
}
trap cleanup_restore_container EXIT

docker run -d --name "$RESTORE_CONTAINER" --volumes-from "$TB_CONTAINER" \
  --entrypoint bash "$TB_IMAGE" -c 'start-db.sh && tail -f /dev/null' >/dev/null

echo "    等待其内置 PostgreSQL 就绪（应用不会随之启动）"
ready=0
for _ in $(seq 1 60); do
  if docker exec -u thingsboard "$RESTORE_CONTAINER" pg_isready -U thingsboard -d thingsboard >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 2
done
# 明确失败而不是超时后仍往下跑 psql：对着一个没就绪的库执行恢复，得到的错误会具有误导性
# （看起来像恢复坏了，其实是等待没做完），不如在这里就说清楚。
if [[ $ready -ne 1 ]]; then
  echo "!!! 等待内置 PostgreSQL 就绪超时，未执行恢复。" >&2
  exit 1
fi

gzip -dc "$DIR/thingsboard.sql.gz" | docker exec -i -u thingsboard "$RESTORE_CONTAINER" psql -U thingsboard -d thingsboard -q

cleanup_restore_container
trap - EXIT

echo "    启动 ThingsBoard"
docker start "$TB_CONTAINER" >/dev/null

echo
echo "恢复完成。业务后台仍是上面确认过的停止状态，需要手动重新启动它，然后运行冒烟测试确认："
echo "    ./bin/smoke -api $API_URL"
