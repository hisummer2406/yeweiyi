#!/usr/bin/env python3
"""验证发布失败后旧镜像和环境不会被错误地留在新版本。无需容器。"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

NEW = 'ghcr.io/example/cloud@sha256:' + 'a' * 64
OLD = 'ghcr.io/example/cloud@sha256:' + 'b' * 64
LOCAL = 'yeweiyi-local/cloud:sha-' + 'c' * 40 + '-r123-1' # Runner 构建、经 SSH 预载（image_source=runner）
SCRIPT = Path(__file__).with_name('remote-deploy.sh').resolve()
MOCK = r"""#!/usr/bin/env bash
set -eu
name=$(basename "$0")
if [[ "$name" == sleep || "$name" == flock ]]; then exit 0; fi
if [[ "$name" == curl ]]; then
  active=$(cat "$TEST_STATE")
  [[ "$SCENARIO" != rollback_fail && ( ( "$SCENARIO" != health_fail && "$SCENARIO" != retagged ) || "$active" == *bbbb* ) ]]
  exit
fi
[[ "$name" == sudo ]] && shift
[[ "$1" == docker ]] && shift
[[ "$1" == --config ]] && shift 2
case "$1" in
  pull) [[ "$SCENARIO" != pull_fail && "$SCENARIO" != local_* ]];;
  image)
    [[ "$SCENARIO" != local_missing ]] || exit 1
    # retagged：上一版引用已改指新镜像（同名标签被覆盖）。
    if [[ "$SCENARIO" == retagged && "${@:3:1}" == *bbbb* ]]; then echo "$NEW_IMAGE"; else printf '%s\n' "${@:3:1}"; fi;;
  inspect)
    if [[ "${*: -1}" == *State.Running* ]]; then echo true; else cat "$TEST_STATE"; fi;;
  compose)
    shift
    while [[ "$1" == --env-file || "$1" == -f ]]; do shift 2; done
    case "$1" in
      run) [[ "$SCENARIO" != migration_fail ]];;
      up)
        image=$(sed -n 's/^API_IMAGE=//p' .env.prod)
        [[ "$SCENARIO" != up_fail || "$image" == *bbbb* ]] || exit 1
        printf '%s\n' "$image" > "$TEST_STATE";;
      logs) exit 0;;
      *) exit 9;;
    esac;;
  *) exit 8;;
esac
"""

class DeployTests(unittest.TestCase):
    def run_case(self, scenario, new=NEW):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            bins = root / 'bin'
            bins.mkdir()
            for name in ('sudo', 'curl', 'sleep', 'flock'):
                p = bins / name
                p.write_text(MOCK)
                p.chmod(0o755)
            config = root / '.env.prod'
            config.write_text('API_IMAGE=' + OLD + '\nJWT_SECRET=keep-existing-value\n')
            state = root / 'state'
            state.write_text(OLD + '\n')
            env = dict(os.environ, PATH=str(bins) + ':' + os.environ['PATH'], SCENARIO=scenario,
                       TEST_STATE=str(state), HEALTH_TRIES='2', NEW_IMAGE=new)
            result = subprocess.run(['bash', str(SCRIPT), str(root), new], env=env,
                                    text=True, capture_output=True, timeout=15)
            ok = scenario in ('success', 'local_success')
            self.assertEqual(result.returncode, 0 if ok else 1, result.stderr + result.stdout)
            if scenario == 'retagged':
                self.assertIn('不自动回滚', result.stderr)
                return
            target = new if ok else OLD
            self.assertIn('API_IMAGE=' + target + '\n', config.read_text())
            self.assertIn('JWT_SECRET=keep-existing-value\n', config.read_text())
            self.assertEqual(state.read_text().strip(), target)

    def test_success(self): self.run_case('success')
    def test_pull_failure_keeps_old_release(self): self.run_case('pull_fail')
    def test_migration_failure_restores_env(self): self.run_case('migration_fail')
    def test_start_failure_restores_old_release(self): self.run_case('up_fail')
    def test_health_failure_restores_old_release(self): self.run_case('health_fail')
    def test_failed_rollback_returns_failure(self): self.run_case('rollback_fail')
    # 预载镜像不 pull（mock 里 pull 会失败）；不在本机时旧环境不动。
    def test_preloaded_local_image(self): self.run_case('local_success', LOCAL)
    def test_missing_local_image_keeps_old_release(self): self.run_case('local_missing', LOCAL)
    def test_retagged_previous_image_is_not_restored(self): self.run_case('retagged')
    def test_rejects_other_image_refs(self): self.run_case('bad_ref', 'yeweiyi-local/cloud:latest')
    # 不带运行编号的旧格式会在同一提交重发时被覆盖，拒绝。
    def test_rejects_local_ref_without_run(self): self.run_case('bad_ref', 'yeweiyi-local/cloud:sha-' + 'c' * 40)

if __name__ == '__main__': unittest.main()
