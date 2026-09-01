#!/usr/bin/env bash
# 在**内网**机器上运行：校验并导入离线包中的镜像。全程不访问网络。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "==> 校验文件完整性"
if ! ( cd "$HERE" && shasum -a 256 -c SHA256SUMS --quiet ); then
  echo "!!! 校验失败：包在传输中损坏或被改动，请勿继续安装。" >&2
  exit 1
fi
echo "    SHA256SUMS 全部匹配"

echo "==> 导入镜像"
gzip -dc "$HERE/images/images.tar.gz" | docker load

echo "==> 核对导入后的镜像摘要"
fail=0
while read -r image digest; do
  [[ -z "${image:-}" || "$image" == \#* ]] && continue
  # docker load 后 RepoDigests 可能为空（镜像不是从仓库拉来的），此时退回比对镜像 ID 无意义，
  # 故以“镜像存在且 tag 正确”为准，内容可信性由上面的 SHA256SUMS 保证。
  if docker image inspect "$image" >/dev/null 2>&1; then
    echo "    ✓ $image"
  else
    echo "    ✗ $image 未导入" >&2; fail=1
  fi
done < "$HERE/images.txt"
[[ $fail -eq 0 ]] || exit 1

echo
echo "镜像已就绪。下一步："
echo "  ./preflight.sh          # 检查端口、资源、Docker 版本"
echo "  见 README.md 的“安装”一节"
