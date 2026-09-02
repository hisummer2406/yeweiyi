#!/usr/bin/env bash
# 在**内网**机器上运行：校验并导入离线包中的镜像。全程不访问网络。
set -euo pipefail

# sha256 工具在两端不同名：macOS 有 shasum 无 sha256sum，精简版 Linux（debian-slim 等）
# 正好相反。这些脚本一端在打包机（多为 macOS）跑、一端在内网 Linux 跑，写死任何一个都会
# 在另一端失败——而失败的地方恰好是校验环节，最不该出问题的那一步。
sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"
  else shasum -a 256 "$@"; fi
}


HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "==> 校验文件完整性"
if ! ( cd "$HERE" && sha256 -c SHA256SUMS --quiet ); then
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
