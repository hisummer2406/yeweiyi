"""发布成功、校验失败不切换、健康检查/静态资源失败回滚。无需真实服务器。"""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('remote-deploy.sh').resolve()


class DeployTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.env = dict(os.environ, DEPLOY_ROOT=str(self.root), PATH=str(self.bin) + ':' + os.environ['PATH'])
        self.command('curl', '''#!/usr/bin/env python3
import os, sys
from pathlib import Path
url = sys.argv[-1]
path = url.split('127.0.0.1/', 1)[1]
if path == os.environ.get('FAIL_PATH'): sys.exit(22)
if path == 'api/devices': print(os.environ.get('API_STATUS', '401'), end='')
elif path == 'healthz': print('{"status":"ok"}')
else:
    target = 'index.html' if path == 'user/login' else path
    sys.stdout.buffer.write((Path(os.environ['DEPLOY_ROOT']) / 'current' / target).read_bytes())
''')
        # macOS 没有 GNU mv -T/flock；Linux CI 使用真实命令。
        if os.uname().sysname == 'Darwin':
            self.command('mv', '''#!/usr/bin/env python3
import os,sys
os.replace(sys.argv[-2], sys.argv[-1])
''')
        if not shutil.which('flock'):
            self.command('flock', '#!/bin/sh\nexit 0\n')
        if not shutil.which('sha256sum'):
            self.command('sha256sum', '''#!/usr/bin/env python3
from pathlib import Path
import hashlib,sys
for line in Path(sys.argv[-1]).read_text().splitlines():
    digest,name=line.split('  ',1)
    if hashlib.sha256(Path(name).read_bytes()).hexdigest()!=digest: sys.exit(1)
''')
        for release in ['old', 'new']:
            directory = self.root / 'releases' / release
            directory.mkdir(parents=True)
            files = {'index.html': release, 'release.txt': release, 'app.js': release, 'app.css': release}
            for name, content in files.items():
                (directory / name).write_text(content)
            (directory / 'SHA256SUMS').write_text(''.join(hashlib.sha256(content.encode()).hexdigest() + '  ' + name + '\n' for name, content in files.items()))
        (self.root / 'current').symlink_to('releases/old')

    def command(self, name, source):
        path = self.bin / name
        path.write_text(source)
        path.chmod(0o755)

    def run_deploy(self, **env):
        return subprocess.run(['bash', str(SCRIPT), 'new'], env=dict(self.env, **env), capture_output=True, text=True)

    def test_success(self):
        result = self.run_deploy()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(os.readlink(self.root / 'current'), 'releases/new')

    def test_corrupt_artifact_keeps_previous(self):
        (self.root / 'releases/new/app.js').write_text('corrupted')
        self.assertNotEqual(self.run_deploy().returncode, 0)
        self.assertEqual(os.readlink(self.root / 'current'), 'releases/old')

    def test_failed_checks_restore_previous(self):
        for path in ['release.txt', 'user/login', 'healthz', 'app.js', 'app.css']:
            with self.subTest(path=path):
                self.assertNotEqual(self.run_deploy(FAIL_PATH=path).returncode, 0)
                self.assertEqual(os.readlink(self.root / 'current'), 'releases/old')

    def test_api_must_still_require_auth(self):
        self.assertNotEqual(self.run_deploy(API_STATUS='200').returncode, 0)
        self.assertEqual(os.readlink(self.root / 'current'), 'releases/old')


if __name__ == '__main__':
    unittest.main()
