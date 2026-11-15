#!/usr/bin/env bash
# 用法：ci-fetch-tool.sh <url> <sha256> <tar 内文件名> <目标目录>
#
# 自托管 Runner 用：把固定版本的预编译工具压缩包缓存在 $YEWEIYI_CI_CACHE/tools，
# 每次都重新校验 sha256（缓存被篡改或下载不完整会重新下载，再不一致就失败），
# 然后只把需要的二进制解到目标目录（通常是 $RUNNER_TEMP/bin，随 job 清掉）。
set -euo pipefail

url="$1" sum="$2" member="$3" dest="$4"
cache="${YEWEIYI_CI_CACHE:?YEWEIYI_CI_CACHE 未设置（应在 Runner 的 .env 中配置）}/tools"
file="$cache/${sum}-$(basename "$url")"

mkdir -p "$cache" "$dest"
if echo "$sum  $file" | sha256sum -c --status - 2>/dev/null; then
  echo "工具缓存命中：$file"
else
  echo "下载 $url"
  curl -fsSL --retry 3 -o "$file.part.$$" "$url"
  mv "$file.part.$$" "$file"
fi
echo "$sum  $file" | sha256sum -c -
tar -xzf "$file" -C "$dest" "$member"
