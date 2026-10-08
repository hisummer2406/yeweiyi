#!/usr/bin/env bash
# 上传完成后切换静态站点；失败时恢复前一版本。
set -euo pipefail
ROOT="${DEPLOY_ROOT:-/var/www/yeweiyi-admin}"
RELEASE="${1:?用法：$0 <版本目录名>}"
[[ "$RELEASE" =~ ^[a-zA-Z0-9_-]+$ ]] || exit 1
cd "$ROOT"
exec 9>.deploy.lock
flock -n 9 || { echo '另一次发布正在运行' >&2; exit 1; }
cd "releases/$RELEASE"
sha256sum -c SHA256SUMS >/dev/null
test -s index.html
test -s release.txt
cd "$ROOT"
PREV="$(readlink current || true)"
switch_to() {
    ln -s "$1" current.next
    mv -Tf current.next current
}
switch_to "releases/$RELEASE"
verify_release() {
   curl -fsS --max-time 10 -H 'Host: 8.154.38.176' http://127.0.0.1/release.txt | cmp - "current/release.txt" &&
   curl -fsS --max-time 10 -H 'Host: 8.154.38.176' http://127.0.0.1/user/login | cmp - current/index.html &&
   curl -fsS --max-time 10 -H 'Host: 8.154.38.176' http://127.0.0.1/healthz >/dev/null &&
   [[ "$(curl -sS --max-time 10 -o /dev/null -w '%{http_code}' -H 'Host: 8.154.38.176' http://127.0.0.1/api/devices)" == 401 ]] || return 1
   while IFS= read -r -d '' asset; do
       local path="${asset#current/}"
       curl -fsS --max-time 10 -H 'Host: 8.154.38.176' "http://127.0.0.1/$path" | cmp - "$asset" || return 1
   done < <(find -L current -type f \( -name '*.js' -o -name '*.css' \) -print0)
}
if verify_release; then
    printf '部署成功：%s\n' "$RELEASE"
else
    if [[ -n "$PREV" ]]; then switch_to "$PREV"; else rm current; fi
    echo '验收失败，已恢复前一站点状态' >&2
    exit 1
fi
