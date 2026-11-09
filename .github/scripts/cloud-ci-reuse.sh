#!/usr/bin/env bash
# 输出 reuse=true|false：<commit> 是否可以直接复用其 PR 已通过的 Cloud CI 结果。
# 判定不了一律 false（照常跑全量），所以这里任何失败都不能让脚本报错。
set -uo pipefail

commit="${1:-HEAD}"
reuse=false
if head="$(git rev-parse -q --verify "$commit^2")" &&
  [ "$(git rev-parse "$commit^{tree}")" = "$(git rev-parse "$head^{tree}")" ]; then
  runs="$(gh api "repos/${GITHUB_REPOSITORY}/actions/runs?head_sha=${head}&event=pull_request&per_page=100" 2>/dev/null || true)"
  if printf '%s' "$runs" | jq -e '
    [.workflow_runs[] | select(.name == "Cloud CI")]
    | sort_by(.run_number) | last | .conclusion == "success"' >/dev/null 2>&1; then
    reuse=true
  fi
fi
echo "reuse=$reuse"
