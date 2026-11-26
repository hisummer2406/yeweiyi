#!/usr/bin/env bash
# 用法：ci-reuse.sh check|record "<工作流名>"，在 actions/checkout 之后运行（PR 上检出的是与 base 的合并提交）。
#   record：全部作业成功后，把本次实际检出的 cloud/、.github/ 目录树作为提交状态（context「已测树/<工作流名>」）
#           记到被测提交上（PR：PR 头；push：本提交）。
#   check ：本次检出的目录树与某个候选提交上记录的已测树相同，输出 reuse=true，否则 reuse=false。
#           候选：PR 为同分支近期该工作流运行过的 PR 头；push 到 main 为合并提交的第二个父提交（PR 头）。
# 比较的是实际测过的树，不是 PR 头或 base 各自的树：base 变化导致合并结果不同时不会误判。
# 判定不了一律 false（照常全量），所以任何失败都不能让脚本报错；record 失败只告警。
set -uo pipefail

mode="${1:?check|record}"
wf="${2:?工作流名}"
repo="${GITHUB_REPOSITORY:?}"
ctx="已测树/$wf"
key="$(git rev-parse HEAD:cloud HEAD:.github 2>/dev/null | paste -sd' ' -)"
[[ "$key" =~ ^[0-9a-f]{40}\ [0-9a-f]{40}$ ]] || key=""

if [[ "$mode" == record ]]; then
  target="$GITHUB_SHA"
  [[ "${GITHUB_EVENT_NAME:-}" == pull_request ]] && target="$(jq -r .pull_request.head.sha "$GITHUB_EVENT_PATH")"
  if [[ -n "$key" ]] && gh api "repos/$repo/statuses/$target" -f state=success -f context="$ctx" \
    -f description="cloud .github = $key" >/dev/null; then
    echo "已在 $target 记录 ${ctx}：$key" >&2
  else
    echo "::warning::未能记录 ${ctx}（不影响本次结果，下次照常全量）" >&2
  fi
  exit 0
fi

reuse=false
why="未找到测过相同目录树的成功运行"
candidates=""
case "${GITHUB_EVENT_NAME:-}" in
pull_request)
  ref="$(jq -r .pull_request.head.ref "$GITHUB_EVENT_PATH")"
  candidates="$(gh api "repos/$repo/actions/runs?event=pull_request&branch=$ref&per_page=30" 2>/dev/null |
    jq -r --arg wf "$wf" '[.workflow_runs[] | select(.name == $wf) | .head_sha] | unique | .[]' 2>/dev/null)"
  ;;
push)
  candidates="$(git rev-parse -q --verify HEAD^2 2>/dev/null)"
  ;;
esac
if [[ -n "$key" ]]; then
  for sha in $candidates; do
    # 提交状态按时间倒序；同一 context 取最新一条。
    desc="$(gh api "repos/$repo/commits/$sha/statuses?per_page=100" 2>/dev/null |
      jq -r --arg c "$ctx" '[.[] | select(.context == $c)][0] | select(.state == "success") | .description' 2>/dev/null)"
    if [[ "$desc" == "cloud .github = $key" ]]; then
      reuse=true; why="与 $sha 的成功运行测过的 cloud/、.github/ 目录树相同"; break
    fi
  done
fi
echo "$wf 复用判定：${reuse}（${why}）" >&2
echo "reuse=$reuse"
