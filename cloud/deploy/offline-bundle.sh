#!/usr/bin/env bash
# 在**有网**的机器上运行：把离线安装所需的一切打成一个自包含的 tar 包。
#
# 产出物刻意不含任何需要联网才能取得的东西——内网机器上除了 Docker 本身，不需要再下载任何字节。
set -euo pipefail

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
  docker pull -q "$image" >/dev/null
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
mapfile -t IMAGES < <(grep -vE '^\s*(#|$)' "$HERE/images.txt" | awk '{print $1}')
docker save "${IMAGES[@]}" | gzip -1 > "$STAGE/images/images.tar.gz"

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
( cd "$STAGE" && find . -type f ! -name SHA256SUMS -exec shasum -a 256 {} + | sort -k2 > SHA256SUMS )

echo "==> 打包"
( cd "$OUT" && tar -czf "yeweiyi-offline-$VERSION.tar.gz" "yeweiyi-offline-$VERSION" )
rm -rf "$STAGE"

BUNDLE="$OUT/yeweiyi-offline-$VERSION.tar.gz"
echo
echo "完成：$BUNDLE"
echo "大小：$(du -h "$BUNDLE" | cut -f1)"
echo "摘要：$(shasum -a 256 "$BUNDLE" | awk '{print $1}')"
echo
echo "把 tar 包与上面这行摘要分开传给内网侧（例如摘要走纸质/电话），"
echo "对方用 offline-load.sh 校验后导入。"
