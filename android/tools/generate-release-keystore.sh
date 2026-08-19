#!/usr/bin/env bash
#
# 生成正式包签名用的 keystore（一次性操作，见 app/build.gradle 的 signingConfigs.release）。
#
# 产物固定写到 app/keystore/release.jks（.gitignore 已排除，不进版本库）。
# storepass/keypass 由 keytool 交互式提示输入，本脚本不接收、不回显、不落盘密码 ——
# 输完之后自己填进 local.properties 的 sansen.releaseStorePassword /
# sansen.releaseKeyAlias / sansen.releaseKeyPassword（见 local.properties.template）。
#
# ⚠️ 这把 key 一旦用来签出正式包发到现场设备，就再也换不掉：Android 要求同一 applicationId
# 的升级包必须用同一把 key 签名，key 或密码丢了，老设备只能卸载重装，不能覆盖升级。
# 生成后请把 app/keystore/release.jks 和三项密码额外备份到仓库之外的地方（密码管理器/
# 加密网盘），不要只留在某一台开发机上。
#
# 用法：
#   tools/generate-release-keystore.sh [别名，默认 yeweiyi-release]
#
set -euo pipefail

usage() {
    sed -n '3,20p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
}

command -v keytool >/dev/null 2>&1 || {
    echo "错误：找不到 keytool，确认 JDK 已安装且在 PATH 里（本项目用 JDK 11）。" >&2
    exit 1
}

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
KEYSTORE_DIR="$REPO_ROOT/app/keystore"
KEYSTORE_FILE="$KEYSTORE_DIR/release.jks"
ALIAS=${1:-yeweiyi-release}

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
    usage
fi

if [ -f "$KEYSTORE_FILE" ]; then
    echo "错误：$KEYSTORE_FILE 已存在，不会覆盖。" >&2
    echo "确实要换 key 的话，先手动移走旧文件——但要清楚这意味着老设备装不了新包的升级版。" >&2
    exit 1
fi

mkdir -p "$KEYSTORE_DIR"

echo "即将生成 ${KEYSTORE_FILE}，别名：${ALIAS}"
echo "接下来 keytool 会交互式提问：keystore 密码、姓名/组织等 DN 信息、key 密码（可直接回车沿用 keystore 密码）。"
echo "有效期设为 30 年——这是一台长期部署在客户现场的设备终端应用，证书中途过期会导致没法出正式升级包。"
echo

keytool -genkeypair \
    -v \
    -keystore "$KEYSTORE_FILE" \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10950

echo
echo "已生成：$KEYSTORE_FILE"
echo "接下来："
echo "  1. 在 local.properties 里填 sansen.releaseStorePassword / sansen.releaseKeyAlias=$ALIAS / sansen.releaseKeyPassword"
echo "  2. 把 release.jks 和三项密码备份到仓库之外的地方"
echo "  3. ./gradlew :app:assembleRelease 验证能出签名包"
