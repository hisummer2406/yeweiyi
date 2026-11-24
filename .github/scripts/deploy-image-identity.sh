#!/usr/bin/env bash
# 用法：deploy-image-identity.sh <ghcr.io/...@sha256:... | yeweiyi-local/cloud:sha-<提交>> <docker save 产生的 tar> <远端 driver-type>
# 输出：远端 `docker load` 该 tar 之后，`docker image inspect --format '{{.Id}}'` 应当得到的值。
#
# 背景：`.Id` 的含义取决于 Docker 镜像存储——
#   经典存储（overlay2 graphdriver）：.Id = 镜像 config 的 digest；
#   containerd 镜像存储（driver-type io.containerd.snapshotter.v1，Docker 29 新装默认）：
#     .Id = 镜像 index/manifest 的 digest，也就是 RepoDigests 里固定的那个 digest。
# 2026-11-02 托管 Runner 换到 26.04 后，就是两端口径不一致导致「远端预载镜像标识不一致」误拦。
# 自托管 Runner（Ubuntu 24.04 + Docker 29）用的是 containerd 存储，生产服务器此前与托管 24.04 一致。
#
# 这里不放宽校验：期望值一律从已固定的 digest 推导，链路上每一步都要对上，否则失败——
#   1. 本机 containerd 存储时：本机 .Id 必须等于固定 digest；
#      tar 的 index.json 必须只指向该 digest；config digest 取自同一个 tar 的 manifest.json。
#      本机经典存储时：config digest 就是本机 .Id（与原来的做法完全相同）。
#   2. 远端经典存储 → 期望 config digest；远端 containerd 存储 → 期望固定 digest。
set -euo pipefail

ref="$1" archive="$2" remote_store="$3"
CONTAINERD=io.containerd.snapshotter.v1
store_of() { docker info --format '{{range .DriverStatus}}{{if eq (index . 0) "driver-type"}}{{index . 1}}{{end}}{{end}}'; }

local_id="$(docker image inspect "$ref" --format '{{.Id}}')"
if [[ "$ref" =~ ^yeweiyi-local/cloud:sha-[a-f0-9]{40}$ ]]; then
  # Runner 本机构建（image_source=runner）：没有仓库 digest。containerd 存储时本机 .Id 就是 index digest，作为固定值；
  # 经典存储时只有 config digest，远端若是 containerd 存储就无从推导，下面会失败。
  pinned=""
  [[ "$(store_of)" == "$CONTAINERD" ]] && pinned="$local_id"
else
  pinned="sha256:${ref##*@sha256:}"
  [[ "$ref" == *@sha256:* && "$pinned" =~ ^sha256:[a-f0-9]{64}$ ]] || { echo "不是固定 digest 的镜像引用：$ref" >&2; exit 1; }
fi
if [[ "$(store_of)" == "$CONTAINERD" ]]; then
  [[ "$local_id" == "$pinned" ]] || { echo "本机镜像 .Id ($local_id) 与固定 digest ($pinned) 不一致" >&2; exit 1; }
  index_digest="$(tar -xOf "$archive" index.json | jq -er '[.manifests[].digest] | unique | if length == 1 then .[0] else error("index.json 含多个镜像") end')"
  [[ "$index_digest" == "$pinned" ]] || { echo "导出包 index.json ($index_digest) 不是固定 digest ($pinned)" >&2; exit 1; }
  config_path="$(tar -xOf "$archive" manifest.json | jq -er 'if length == 1 then .[0].Config else error("manifest.json 含多个镜像") end')"
  [[ "$config_path" =~ ^blobs/sha256/([a-f0-9]{64})$ ]] || { echo "无法识别的 config 路径：$config_path" >&2; exit 1; }
  config="sha256:${BASH_REMATCH[1]}"
  # config 必须真的在导出包里，并且内容与其 digest 相符。
  [[ "sha256:$(tar -xOf "$archive" "$config_path" | sha256sum | cut -d' ' -f1)" == "$config" ]] || { echo "导出包内 config 内容与 digest 不符" >&2; exit 1; }
else
  config="$local_id"
fi
[[ "$config" =~ ^sha256:[a-f0-9]{64}$ ]] || { echo "config digest 无效：$config" >&2; exit 1; }

case "$remote_store" in
  "$CONTAINERD") [[ -n "$pinned" ]] || { echo "本机经典存储构建的镜像无法推导 containerd 远端的标识" >&2; exit 1; }; echo "$pinned" ;;
  "") echo "$config" ;;
  *) echo "无法识别的远端镜像存储：$remote_store" >&2; exit 1 ;;
esac
