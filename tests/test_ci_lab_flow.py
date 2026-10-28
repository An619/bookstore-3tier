"""Simulate deploy/local/ci/lab.sh: env generation, ordering, token handling."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
CI_DIR = ROOT / 'deploy/local/ci'

FAKE_DOCKER = '''#!/bin/sh
echo "docker $*" >> "$LAB_TEST_LOG"
case "$1 $2" in
  "info ") exit 0 ;;
  "compose version") echo "Docker Compose v2"; exit 0 ;;
esac
exit 0
'''

FAKE_CURL = '''#!/bin/sh
echo "curl $*" >> "$LAB_TEST_LOG"
case "$*" in
  *crumbIssuer*) echo '{"crumbRequestField":"Jenkins-Crumb","crumb":"abc123"}' ;;
  *"%{http_code}"*) echo 201 ;;
  *) exit 0 ;;
esac
'''

# Only the Sonar bootstrap is faked; the secrets helper runs for real.
FAKE_PYTHON = '''#!/bin/sh
for arg in "$@"; do
  case "$arg" in
    */sonar-bootstrap.py|sonar-bootstrap.py)
      echo "error: simulated bootstrap" >&2
      echo "TOKEN-abcdefghijklmnop"
      exit 0 ;;
  esac
done
exec "@REAL_PYTHON@" "$@"
'''


class CiLabFlowTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.log = self.root / 'calls.log'
        self.log.touch()
        real_python = shutil.which('python3')
        for name, body in (('docker', FAKE_DOCKER), ('curl', FAKE_CURL),
                           ('python3', FAKE_PYTHON.replace('@REAL_PYTHON@', real_python))):
            path = self.bin / name
            path.write_text(body)
            path.chmod(0o755)
        # The CI lab writes fixed relative paths, so copy it into a scratch tree.
        self.work = self.root / 'repo'
        shutil.copytree(CI_DIR, self.work / 'deploy/local/ci')
        (self.work / 'scripts').mkdir(parents=True)
        for script in ('lab-secrets.py', 'lab-tools.sh'):
            shutil.copy(ROOT / 'scripts' / script, self.work / 'scripts' / script)
        self.env = dict(os.environ, PATH=f'{self.bin}:/usr/bin:/bin',
                        LAB_TEST_LOG=str(self.log), DOCKER_GID='999')

    def run_lab(self, *args):
        return subprocess.run(['sh', str(self.work / 'deploy/local/ci/lab.sh'), *args],
                              env=self.env, capture_output=True, text=True)

    def env_file(self):
        return self.work / 'deploy/local/ci/.env'

    def test_up_generates_private_env_and_orders_sonar_before_jenkins(self):
        result = self.run_lab('up')
        self.assertEqual(0, result.returncode, result.stderr)
        env = self.env_file()
        self.assertEqual(0o600, env.stat().st_mode & 0o777)
        values = dict(line.split('=', 1) for line in env.read_text().splitlines()
                      if '=' in line and not line.startswith('#'))
        self.assertEqual('999', values['DOCKER_GID'])
        self.assertEqual('TOKEN-abcdefghijklmnop', values['SONAR_TOKEN'])
        self.assertGreaterEqual(len(values['JENKINS_ADMIN_PASSWORD']), 20)
        self.assertNotIn('admin:admin', env.read_text())

        calls = self.log.read_text().splitlines()
        sonar = next(i for i, c in enumerate(calls) if 'sonarqube' in c)
        jenkins = next(i for i, c in enumerate(calls) if 'jenkins' in c)
        self.assertLess(sonar, jenkins, 'SonarQube must be ready before Jenkins starts')
        self.assertTrue(any('compose up -d sonar-db sonarqube' in c for c in calls))
        self.assertTrue(any('compose up -d --build jenkins' in c for c in calls))
        # The token is written only to .env, never echoed to the terminal.
        self.assertNotIn('TOKEN-abcdefghijklmnop', result.stdout)

    def test_rerun_keeps_existing_generated_secrets(self):
        self.assertEqual(0, self.run_lab('up').returncode)
        first = self.env_file().read_text()
        self.log.write_text('')
        result = self.run_lab('up')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(first, self.env_file().read_text())
        # A token already in .env is reused instead of generating another one.
        self.assertFalse(any('sonar-bootstrap' in c for c in self.log.read_text().splitlines()))

    def test_run_triggers_the_pipeline(self):
        self.assertEqual(0, self.run_lab('up').returncode)
        result = self.run_lab('run')
        self.assertEqual(0, result.returncode, result.stderr)
        calls = self.log.read_text().splitlines()
        self.assertTrue(any('/job/bookstore-pipeline/build' in c for c in calls))
        # The build POST must carry the CSRF crumb and never print the password.
        build = next(c for c in calls if '/job/bookstore-pipeline/build' in c)
        self.assertIn('Jenkins-Crumb:abc123', build)
        admin_password = dict(
            line.split('=', 1) for line in self.env_file().read_text().splitlines() if '=' in line
        )['JENKINS_ADMIN_PASSWORD']
        self.assertNotIn(admin_password, result.stdout)
        self.assertNotIn(admin_password, result.stderr)

    def test_reset_removes_env_and_volumes(self):
        self.assertEqual(0, self.run_lab('up').returncode)
        result = self.run_lab('reset')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(self.env_file().exists())
        self.assertIn('down -v', self.log.read_text())


if __name__ == '__main__':
    unittest.main()
