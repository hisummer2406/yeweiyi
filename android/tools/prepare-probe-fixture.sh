#!/usr/bin/env bash
#
# 把设备上采集到的探头报文导入为版本化 fixture（tasks.md §1.7）。
#
# 设备端由 manager/ProbeTrafficRecorder 写出 /sdcard/yeweiyi-probe-record/probe-<日期>.tsv
# （需在 local.properties 里设 sansen.probeRecording=true 后重新构建并安装）。
# 本脚本把这类文件拷进 app/src/test/resources/goldenmaster/，同时在 manifest.tsv 里
# 登记它的探头型号、采集日期与工况 —— 没有这些说明，半年后没人说得清一份 fixture
# 覆盖了什么。清单同时也是重放测试发现 fixture 的唯一途径。
#
# 用法：
#   tools/prepare-probe-fixture.sh <采集文件> <探头型号> <工况描述> [输出名]
#
#   <采集文件>    从设备拉下来的 .tsv（adb pull /sdcard/yeweiyi-probe-record/... ）
#   <探头型号>    0=中诚 1=LD 2=LD2；多种用逗号分隔，如 "0,1"
#   <工况描述>    例如 "正常工况连续24h" 或 "人为触发高低位报警"
#   [输出名]      可选，默认 probe-<采集日期>-<型号>.tsv
#
# 示例：
#   adb pull /sdcard/yeweiyi-probe-record/probe-20260901.tsv /tmp/
#   tools/prepare-probe-fixture.sh /tmp/probe-20260901.tsv 1 "LD探头正常工况连续24h"
#
set -euo pipefail

usage() {
    sed -n '3,22p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
}

[ $# -ge 3 ] || usage

SRC=$1
PROBE_TYPES=$2
CONDITIONS=$3

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
DEST_DIR="$REPO_ROOT/app/src/test/resources/goldenmaster"
MANIFEST="$DEST_DIR/manifest.tsv"

[ -f "$SRC" ] || { echo "错误：采集文件不存在: $SRC" >&2; exit 1; }

if ! printf '%s' "$PROBE_TYPES" | grep -Eq '^[0-9]+(,[0-9]+)*$'; then
    echo "错误：探头型号须为数字，多种用逗号分隔（如 0,1），收到: $PROBE_TYPES" >&2
    exit 1
fi

# ---- 采集日期：优先取文件里第一条记录的时间戳，取不到再退回文件名 ----
FIRST_TS=$(grep -m1 -E '^(IN|OUT)	[0-9]+	' "$SRC" | cut -f2 || true)
if [ -n "${FIRST_TS:-}" ]; then
    SECS=$((FIRST_TS / 1000))
    if date -r "$SECS" +%Y%m%d >/dev/null 2>&1; then
        CAPTURED_ON=$(date -r "$SECS" +%Y%m%d)          # BSD/macOS
    else
        CAPTURED_ON=$(date -d "@$SECS" +%Y%m%d)         # GNU/Linux
    fi
else
    CAPTURED_ON=$(basename "$SRC" | grep -oE '[0-9]{8}' | head -1 || true)
    [ -n "$CAPTURED_ON" ] || { echo "错误：无法确定采集日期，文件里没有合法记录" >&2; exit 1; }
fi

DEST_NAME=${4:-"probe-${CAPTURED_ON}-$(printf '%s' "$PROBE_TYPES" | tr ',' '-').tsv"}
DEST="$DEST_DIR/$DEST_NAME"

# ---- 采集质量体检：全是无产出配对的数据看着有几万行，实际一条都用不了 ----
IN_COUNT=$(grep -c '^IN	' "$SRC" || true)
OUT_COUNT=$(grep -c '^OUT	' "$SRC" || true)
echo "采集文件体检：IN=$IN_COUNT 条，OUT=$OUT_COUNT 条"

[ "$IN_COUNT" -gt 0 ] || { echo "错误：没有任何 IN 记录，这份采集不可用" >&2; exit 1; }
if [ "$OUT_COUNT" -eq 0 ]; then
    echo "错误：没有任何 OUT 记录 —— 全程解析失败（探头未接上？波特率不对？）" >&2
    echo "      这份数据无法作为 golden master，请重新采集。" >&2
    exit 1
fi

for t in $(printf '%s' "$PROBE_TYPES" | tr ',' ' '); do
    n=$(awk -F'\t' -v t="$t" '$1=="IN" && $3==t' "$SRC" | wc -l | tr -d ' ')
    echo "  探头型号 $t: $n 条输入报文"
    [ "$n" -gt 0 ] || { echo "错误：声明了型号 $t 但数据里一条都没有" >&2; exit 1; }
done

# ---- 落盘 ----
mkdir -p "$DEST_DIR"
if [ -e "$DEST" ]; then
    echo "错误：$DEST 已存在。换个输出名，或先删除旧文件。" >&2
    exit 1
fi
cp "$SRC" "$DEST"

if [ ! -f "$MANIFEST" ]; then
    {
        echo "# golden master fixture 清单。由 tools/prepare-probe-fixture.sh 维护。"
        echo "#"
        echo "# 重放测试（ProbeReplayTest）靠本清单发现 fixture —— 没登记的文件不会被加载。"
        echo "# 清单为空时重放测试整体跳过，那是现场数据尚未采集回来的正常状态。"
        echo "#"
        echo "# 列：文件名<TAB>探头型号(逗号分隔)<TAB>采集日期<TAB>工况描述"
    } > "$MANIFEST"
fi
printf '%s\t%s\t%s\t%s\n' "$DEST_NAME" "$PROBE_TYPES" "$CAPTURED_ON" "$CONDITIONS" >> "$MANIFEST"

echo
echo "已导入: app/src/test/resources/goldenmaster/$DEST_NAME"
echo "已登记: $DEST_NAME  型号=$PROBE_TYPES  采集于=$CAPTURED_ON  工况=$CONDITIONS"
echo
echo "下一步：./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest"
echo "        ProbeReplayTest 会从跳过转为实际校验这批数据。"
