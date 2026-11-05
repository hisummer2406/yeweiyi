#!/bin/sh
# 校验本目录下的 fixture 与 MANIFEST.json 的 sha256 一致。
#
# 存在的理由：这批文件是云端与 Android 的共同契约，而"共同"只有在双方消费的确实是同一批字节时
# 才成立。云端由 internal/protocol 的 TestFixtureManifestMatchesFiles 强制；Android 侧没有 Go
# 工具链，所以这里提供一个只依赖 POSIX sh + awk + sha256 工具的等价物，可直接挂进 Gradle 或 CI。
#
# 用法：
#   sh fixtures/protocol/v1/verify-manifest.sh            # 校验脚本所在目录
#   sh verify-manifest.sh /path/to/fixtures/protocol/v1   # 校验指定目录
#
# 退出码 0 = 一致；1 = 有文件缺失、多余或内容漂移。
#
# 刻意不自动修复：改了 fixture 就必须手动改 MANIFEST.json 里的 sha256，这一步的摩擦正是目的
# ——它保证任何一侧的改名/改单位都会同时让两个仓库的构建变红，而不是等到真实设备上才发现。

set -eu

dir="${1:-$(dirname "$0")}"
manifest="$dir/MANIFEST.json"

[ -f "$manifest" ] || { echo "verify-manifest: 找不到 $manifest" >&2; exit 1; }

if command -v sha256sum >/dev/null 2>&1; then
    hash_of() { sha256sum "$1" | cut -d' ' -f1; }
elif command -v shasum >/dev/null 2>&1; then
    hash_of() { shasum -a 256 "$1" | cut -d' ' -f1; }
else
    echo "verify-manifest: 既没有 sha256sum 也没有 shasum" >&2
    exit 1
fi

# 把 MANIFEST.json 压成每行 "<file> <sha256>"。用 awk 而不是 jq：Android 的构建机上不保证有
# jq，而这份清单的格式是本仓库自己写死的，不需要通用 JSON 解析器。
pairs=$(awk '
    match($0, /"file"[ \t]*:[ \t]*"[^"]*"/) {
        s = substr($0, RSTART, RLENGTH); sub(/^.*"file"[ \t]*:[ \t]*"/, "", s); sub(/"$/, "", s)
        f = s; next
    }
    match($0, /"sha256"[ \t]*:[ \t]*"[^"]*"/) {
        s = substr($0, RSTART, RLENGTH); sub(/^.*"sha256"[ \t]*:[ \t]*"/, "", s); sub(/"$/, "", s)
        if (f != "") { print f, s; f = "" }
    }
' "$manifest")

[ -n "$pairs" ] || { echo "verify-manifest: $manifest 里没有解析出任何 fixture 条目" >&2; exit 1; }

# 失败标记走文件而不是变量：下面的 while 在管道里跑，是子 shell，变量改不回来。
flag="${TMPDIR:-/tmp}/verify-manifest.$$"
: > "$flag"
trap 'rm -f "$flag"' EXIT INT TERM

count=0
echo "$pairs" | while read -r file sha; do
    if [ ! -f "$dir/$file" ]; then
        echo "缺失：MANIFEST.json 列出了 ${file}，但目录里没有" >&2
        echo x >> "$flag"
        continue
    fi
    actual=$(hash_of "$dir/$file")
    if [ "$actual" != "$sha" ]; then
        echo "漂移：$file" >&2
        echo "  MANIFEST.json: $sha" >&2
        echo "  实际内容:      $actual" >&2
        echo x >> "$flag"
    fi
done

for f in "$dir"/*.json; do
    base=$(basename "$f")
    [ "$base" = "MANIFEST.json" ] && continue
    if ! echo "$pairs" | cut -d' ' -f1 | grep -qx "$base"; then
        echo "未登记：$base 在目录里，但 MANIFEST.json 没有列它" >&2
        echo x >> "$flag"
    fi
done

if [ -s "$flag" ]; then
    echo "" >&2
    echo "fixture 与清单不一致。若这是一次有意的协议改动，请同步更新 MANIFEST.json 的 sha256，" >&2
    echo "并让 yeweiyi-cloud 与 zhongchengyeweiyi 两侧一起改——这正是这批文件共享的意义。" >&2
    exit 1
fi

count=$(echo "$pairs" | wc -l | tr -d ' ')
echo "verify-manifest: OK，$count 个 fixture 与 MANIFEST.json 一致"
