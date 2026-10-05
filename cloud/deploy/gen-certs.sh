#!/usr/bin/env bash
# 为 ThingsBoard 的 MQTT/TLS 监听器生成自签 CA 与服务端证书。
#
# 为什么自签：离线内网没有公网 CA，也无法做 ACME 续期。信任链靠把 CA 证书预置到设备侧
# 建立——这是这类部署的常规做法，不是将就。
#
#   ./gen-certs.sh <主机名或IP> [更多主机名/IP...]
#
# 主机名必须写全：设备连的是哪个名字，证书里就得有哪个名字，否则设备会因为主机名不匹配
# 而拒绝连接。写 IP 也可以，脚本会自动识别放进 IP SAN。
set -euo pipefail

OUT="${CERT_DIR:-./deploy/certs}"
CA_DAYS="${CA_DAYS:-3650}"       # CA 十年：离线环境换 CA 要重新分发到每台设备，代价很高
SRV_DAYS="${SRV_DAYS:-825}"      # 服务端证书约 2.25 年，是多数 TLS 客户端接受的上限

[[ $# -ge 1 ]] || { echo "用法：$0 <主机名或IP> [更多...]" >&2; echo "例：  $0 tb.example.mil 10.0.0.10 localhost" >&2; exit 1; }

command -v openssl >/dev/null || { echo "!!! 需要 openssl" >&2; exit 1; }
mkdir -p "$OUT"; chmod 700 "$OUT"

# SAN 列表：域名进 DNS，IP 进 IP。写错位置会导致客户端校验失败，所以这里自动判别。
san=""; i=1; j=1
for h in "$@"; do
  if [[ "$h" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then san+="IP.$j = $h"$'\n'; j=$((j+1))
  else san+="DNS.$i = $h"$'\n'; i=$((i+1)); fi
done

echo "==> 生成 CA（有效期 ${CA_DAYS} 天）"
openssl genrsa -out "$OUT/ca.key" 4096 2>/dev/null
openssl req -x509 -new -nodes -key "$OUT/ca.key" -sha256 -days "$CA_DAYS" \
  -subj "/CN=yeweiyi-internal-ca/O=yeweiyi" -out "$OUT/ca.pem" 2>/dev/null

echo "==> 生成服务端证书（有效期 ${SRV_DAYS} 天）"
cat > "$OUT/server.cnf" <<CNF
[req]
distinguished_name = dn
req_extensions     = ext
prompt             = no
[dn]
CN = ${1}
O  = yeweiyi
[ext]
basicConstraints = CA:FALSE
keyUsage         = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName   = @alt
[alt]
${san}
CNF

openssl genrsa -out "$OUT/mqttserver_key.pem" 2048 2>/dev/null
openssl req -new -key "$OUT/mqttserver_key.pem" -out "$OUT/server.csr" -config "$OUT/server.cnf" 2>/dev/null
openssl x509 -req -in "$OUT/server.csr" -CA "$OUT/ca.pem" -CAkey "$OUT/ca.key" -CAcreateserial \
  -out "$OUT/mqttserver.pem" -days "$SRV_DAYS" -sha256 \
  -extfile "$OUT/server.cnf" -extensions ext 2>/dev/null

# 私钥格式：**PKCS#8、不加口令**。这是试出来的，三种组合里只有这一种能被 ThingsBoard 读取：
#
#   openssl genrsa 的传统 PKCS#1（BEGIN RSA PRIVATE KEY）              ✗
#   PKCS#8 + AES-256 口令（BEGIN ENCRYPTED PRIVATE KEY）+ 口令         ✗
#   PKCS#8 无口令（BEGIN PRIVATE KEY）+ MQTT_SSL_PEM_KEY_PASSWORD=""   ✓
#
# 两种失败都只报同一句 "Unable to load private key neither from certFile nor from keyFile"，
# 不提示是格式问题，很容易被误判成路径写错或口令不对，所以在此写明。
#
# 不加口令的保护来自文件权限（600）与容器内的只读挂载。加口令并不更安全——口令必须同时
# 写在 compose 文件里服务才起得来，等于把钥匙和锁放进同一个抽屉。
echo "==> 转为 PKCS#8（ThingsBoard 只认这个格式）"
openssl pkcs8 -topk8 -nocrypt -in "$OUT/mqttserver_key.pem" -out "$OUT/mqttserver_key_p8.pem"
mv "$OUT/mqttserver_key_p8.pem" "$OUT/mqttserver_key.pem"

rm -f "$OUT/server.csr" "$OUT/server.cnf" "$OUT/ca.srl"
chmod 600 "$OUT"/*.pem "$OUT/ca.key"

echo
echo "生成于 ${OUT}："
echo "    ca.pem              CA 证书 —— **分发到每台设备**，设备用它校验服务端"
echo "    ca.key              CA 私钥 —— **不要分发**，离线保管，仅签发新证书时使用"
echo "    mqttserver.pem      服务端证书（挂给 ThingsBoard）"
echo "    mqttserver_key.pem  服务端私钥（PKCS#8 无口令，靠 600 权限保护；挂给 ThingsBoard）"
echo
echo "证书覆盖的名字："
openssl x509 -in "$OUT/mqttserver.pem" -noout -text | grep -A1 "Subject Alternative Name" | tail -1 | sed 's/^/    /'
echo "有效期至：$(openssl x509 -in "$OUT/mqttserver.pem" -noout -enddate | cut -d= -f2)"
echo
echo "启用 TLS："
echo "  docker compose -f docker-compose.yml -f docker-compose.thingsboard.yml \\"
echo "                 -f docker-compose.thingsboard-tls.yml up -d"
