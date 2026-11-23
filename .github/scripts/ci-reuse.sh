#!/usr/bin/env bash
# 用法：ci-reuse.sh "<工作流名>"；输出 reuse=true|false（写入 $GITHUB_OUTPUT 用）与一行原因到 stderr。
# 判定的是"本次要测的 cloud/ 与 .github/ 是否与某次已成功的同名工作流完全相同"，相同就不必再跑。
# 判定不了一律 false（照常全量），所以任何失败都不能让脚本报错。
#
# - pull_request：本次 PR 头与 main（base）的 cloud/、.github/ 目录树，都与同分支上一次成功运行时的
#   PR 头、base 相同（合并结果因此相同）。只有 miniapp/android 的推送、Codex 评审后的小改动都落在这里。
# - push 到 main：合并提交与其 PR 头同一棵树，且该 PR 头的同名工作流已成功（原 cloud-ci-reuse.sh 的规则）。
set -uo pipefail

wf="${1:?工作流名}"
repo="${GITHUB_REPOSITORY:?}"
reuse=false
why="未找到可复用的成功运行"

# 某个提交里 cloud/ 与 .github/ 的目录树 sha（两行），取不到返回空。
dirs() {
  local tree
  tree="$(gh api "repos/$repo/git/commits/$1" -q .tree.sha 2>/dev/null)" || return 0
  gh api "repos/$repo/git/trees/$tree" -q '.tree[] | select(.path=="cloud" or .path==".github") | .path + " " + .sha' 2>/dev/null | sort
}

case "${GITHUB_EVENT_NAME:-}" in
pull_request)
  head="$(jq -r .pull_request.head.sha "$GITHUB_EVENT_PATH")"
  base="$(jq -r .pull_request.base.sha "$GITHUB_EVENT_PATH")"
  ref="$(jq -r .pull_request.head.ref "$GITHUB_EVENT_PATH")"
  now="$(dirs "$head")|$(dirs "$base")"
  if [[ "$now" == *cloud*cloud* ]]; then
    runs="$(gh api "repos/$repo/actions/runs?event=pull_request&branch=$ref&status=success&per_page=30" 2>/dev/null || true)"
    while read -r prev_head prev_base; do
      [[ -n "$prev_head" && -n "$prev_base" && "$prev_head" != "$head" ]] || continue
      if [[ "$(dirs "$prev_head")|$(dirs "$prev_base")" == "$now" ]]; then
        reuse=true; why="与 $prev_head 的成功运行 cloud/、.github/ 相同（base 也相同）"; break
      fi
    done < <(printf '%s' "$runs" | jq -r --arg wf "$wf" \
      '.workflow_runs[] | select(.name == $wf) | [.head_sha, (.pull_requests[0].base.sha // "")] | @tsv' 2>/dev/null)
  fi
  ;;
push)
  commit="${GITHUB_SHA:?}"
  if pr_head="$(git rev-parse -q --verify "$commit^2")" &&
    [ "$(git rev-parse "$commit^{tree}")" = "$(git rev-parse "$pr_head^{tree}")" ]; then
    runs="$(gh api "repos/$repo/actions/runs?head_sha=$pr_head&event=pull_request&per_page=100" 2>/dev/null || true)"
    if printf '%s' "$runs" | jq -e --arg wf "$wf" \
      '[.workflow_runs[] | select(.name == $wf)] | sort_by(.run_number) | last | .conclusion == "success"' >/dev/null 2>&1; then
      reuse=true; why="合并提交与 PR 头 $pr_head 同一棵树，其 $wf 已成功"
    fi
  fi
  ;;
esac
echo "$wf 复用判定：${reuse}（${why}）" >&2
echo "reuse=$reuse"
