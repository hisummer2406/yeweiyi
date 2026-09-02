#!/usr/bin/env bash
# 在**有网**的机器上运行：把离线安装所需的一切打成一个自包含的 tar 包。
#
# 产出物刻意不含任何需要联网才能取得的东西——内网机器上除了 Docker 本身，不需要再下载任何字节。
set -euo pipefail

# sha256 工具在两端不同名：macOS 有 shasum 无 sha256sum，精简版 Linux（debian-slim 等）
# 正好相反。这些脚本一端在打包机（多为 macOS）跑、一端在内网 Linux 跑，写死任何一个都会
# 在另一端失败——而失败的地方恰好是校验环节，最不该出问题的那一步。
sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"
  else shasum -a 256 "$@"; fi
}


HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
OUT="${1:-$ROOT/dist}"
VERSION="$(git -C "$ROOT" describe --tags --always --dirty 2>/dev/null || echo unknown)"
STAGE="$OUT/yeweiyi-offline-$VERSION"

echo "==> 输出目录 $STAGE"
rm -rf "$STAGE"; mkdir -p "$STAGE/images" "$STAGE/bin"

# 1) 镜像：按 images.txt 拉取并校验摘要，再导出
echo "==> 拉取并校验镜像"
while read -r image digest; do
  [[ -z "${image:-}" || "$image" == \#* ]] && continue
  echo "    $image"
  # 拉取失败但本地已有该镜像时继续：真正的保证来自下面的摘要校验，而不是"这次是刚拉的"。
  # 打包机的网络抖一下就让整个流程重来（每次要重新导出 1.1 GB）是没必要的。
  if ! docker pull -q "$image" >/dev/null 2>&1; then
    if docker image inspect "$image" >/dev/null 2>&1; then
      echo "        （拉取失败，改用本地已有镜像；摘要仍会校验）"
    else
      echo "!!! 拉取失败且本地无此镜像：$image" >&2; exit 1
    fi
  fi
  actual="$(docker image inspect "$image" --format '{{index .RepoDigests 0}}' | sed 's/.*@//')"
  if [[ "$actual" != "$digest" ]]; then
    # 不是洁癖：tag 可以被重新推送指向不同内容，摘要不符意味着你打进包里的东西
    # 和清单声称的不是同一个。宁可在这里停下，也不要把一个来历不明的镜像送进内网。
    echo "!!! 摘要不符 $image" >&2
    echo "    清单: $digest" >&2
    echo "    实际: $actual" >&2
    echo "    若确属有意升级，请先更新 deploy/images.txt。" >&2
    exit 1
  fi
done < "$HERE/images.txt"

echo "==> 导出镜像（约 1.2 GB，需几分钟）"
# 不用 mapfile：它是 bash 4+ 的内建命令，而 macOS 自带的是 bash 3.2。打包机很可能就是
# 开发者的 Mac，脚本必须在那里也能跑。
IMAGES=""
while read -r image _; do
  [[ -z "${image:-}" || "$image" == \#* ]] && continue
  IMAGES="$IMAGES $image"
done < "$HERE/images.txt"
docker save $IMAGES | gzip -1 > "$STAGE/images/images.tar.gz"

# 2) 二进制：静态编译，内网机器无需 Go 工具链
echo "==> 编译 Go 二进制（linux/amd64，静态）"
for cmd in server smoke tbdevicesim; do
  CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
    go build -C "$ROOT" -trimpath -ldflags="-s -w -X main.version=$VERSION" \
    -o "$STAGE/bin/$cmd" "./cmd/$cmd"
done

# 3) 部署与运行所需的文件
cp "$ROOT/docker-compose.yml" "$ROOT/docker-compose.thingsboard.yml" \
   "$ROOT/docker-compose.thingsboard-tls.yml" "$STAGE/"
cp "$ROOT/config.example.yaml" "$STAGE/"
cp -r "$ROOT/db/migrations" "$STAGE/migrations"
cp -r "$HERE/profiles" "$STAGE/profiles"
cp "$HERE/images.txt" "$HERE/offline-load.sh" "$HERE/preflight.sh" \
   "$HERE/backup.sh" "$HERE/restore.sh" "$HERE/gen-certs.sh" "$HERE/README.md" "$STAGE/"
cp "$HERE/LICENSE-thingsboard.txt" "$STAGE/"
chmod +x "$STAGE"/*.sh

# 4) 校验清单：内网侧据此确认包在传输中未被截断或篡改
echo "==> 生成校验清单"
( cd "$STAGE" && find . -type f ! -name SHA256SUMS | while read -r f; do sha256 "$f"; done | sort -k2 > SHA256SUMS )

echo "==> 打包"
# COPYFILE_DISABLE 与 --no-xattrs：macOS 的 tar 默认会把扩展属性写进归档，GNU tar 解包时
# 会对每个文件打印 "Ignoring unknown extended header keyword 'LIBARCHIVE.xattr...'"。
# 功能上无害，但安装时刷一屏警告会让人以为包坏了——交付物不该让人第一步就怀疑。
( cd "$OUT" && COPYFILE_DISABLE=1 tar --no-xattrs -czf "yeweiyi-offline-$VERSION.tar.gz" "yeweiyi-offline-$VERSION" 2>/dev/null \
  || COPYFILE_DISABLE=1 tar -czf "yeweiyi-offline-$VERSION.tar.gz" "yeweiyi-offline-$VERSION" )
rm -rf "$STAGE"

BUNDLE="$OUT/yeweiyi-offline-$VERSION.tar.gz"
echo
echo "完成：$BUNDLE"
echo "大小：$(du -h "$BUNDLE" | cut -f1)"
echo "摘要：$(sha256 "$BUNDLE" | awk '{print $1}')"
echo
echo "把 tar 包与上面这行摘要分开传给内网侧（例如摘要走纸质/电话），"
echo "对方用 offline-load.sh 校验后导入。"
