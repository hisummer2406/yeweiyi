#!/usr/bin/env bash
# 生成 BrokerTlsTest 用的自签证书夹具。
#
# 为什么把证书提交进仓库而不是每次测试临时生成：JVM 单测里现造一张 X.509 需要
# BouncyCastle，而这个项目的依赖是一堆定死的本地 jar（见 CLAUDE.md），为一个测试
# 引入新依赖不划算。这些是**测试专用**的假证书，私钥一起提交是故意的，它们只用来
# 在本机 127.0.0.1 上起一个临时 TLS 服务端，不对应任何真实部署。
#
# 有效期 20 年，到期后（或需要改 SAN 时）重跑本脚本：
#     tools/generate-tls-test-fixtures.sh
#
# 生成的内容对应云端 deploy/gen-certs.sh 的产物形态：CA + 服务端证书 + PKCS#8 私钥。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$HERE/app/src/test/resources/thingsboard/tls"
DAYS=7300

command -v openssl >/dev/null || { echo "!!! 需要 openssl" >&2; exit 1; }
mkdir -p "$OUT"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# $1=前缀 $2=CN $3=SAN 段内容（可为空表示不带 SAN 扩展）
issue() {
  local prefix="$1" cn="$2" san="$3"
  openssl genrsa -out "$TMP/$prefix.key" 2048 2>/dev/null
  if [[ -n "$san" ]]; then
    cat > "$TMP/$prefix.cnf" <<CNF
[req]
distinguished_name = dn
prompt             = no
[dn]
CN = $cn
O  = yeweiyi-test
[ext]
basicConstraints = CA:FALSE
keyUsage         = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName   = @alt
[alt]
$san
CNF
  else
    cat > "$TMP/$prefix.cnf" <<CNF
[req]
distinguished_name = dn
prompt             = no
[dn]
CN = $cn
O  = yeweiyi-test
[ext]
basicConstraints = CA:FALSE
keyUsage         = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
CNF
  fi
  openssl req -new -key "$TMP/$prefix.key" -out "$TMP/$prefix.csr" -config "$TMP/$prefix.cnf" 2>/dev/null
  openssl x509 -req -in "$TMP/$prefix.csr" -CA "$4" -CAkey "$5" -CAcreateserial \
    -out "$OUT/$prefix-cert.pem" -days "$DAYS" -sha256 \
    -extfile "$TMP/$prefix.cnf" -extensions ext 2>/dev/null
  # PKCS#8 无口令：测试里用 PKCS8EncodedKeySpec 直接读，不需要额外的解析库。
  openssl pkcs8 -topk8 -nocrypt -in "$TMP/$prefix.key" -out "$OUT/$prefix-key.pem"
}

make_ca() {
  local name="$1" cn="$2"
  openssl genrsa -out "$TMP/$name.key" 2048 2>/dev/null
  openssl req -x509 -new -nodes -key "$TMP/$name.key" -sha256 -days "$DAYS" \
    -subj "/CN=$cn/O=yeweiyi-test" -out "$OUT/$name.pem" 2>/dev/null
}

echo "==> 生成受信任的测试 CA 与服务端证书"
make_ca ca yeweiyi-test-ca
# 同时覆盖域名、通配符和回环 IP：主机名校验的三种情况一张证书测完。
issue server tb.example.test "DNS.1 = tb.example.test
DNS.2 = *.wild.example.test
IP.1 = 127.0.0.1" "$OUT/ca.pem" "$TMP/ca.key"

echo "==> 生成另一个不受信任的 CA 与它签的服务端证书（用于验证签发方不对时会被拒）"
make_ca other-ca yeweiyi-test-rogue-ca
issue rogue tb.example.test "DNS.1 = tb.example.test
IP.1 = 127.0.0.1" "$OUT/other-ca.pem" "$TMP/other-ca.key"

echo "==> 生成一张只有 CN、没有 SAN 的证书（用于验证不回退到 CN）"
issue nosan tb.example.test "" "$OUT/ca.pem" "$TMP/ca.key"

rm -f "$OUT/ca.srl" "$OUT/other-ca.srl"
echo
echo "生成于 ${OUT}："
ls -1 "$OUT"
