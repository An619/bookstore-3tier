"""Verify retry and failure reporting without a Docker daemon."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ComposeUpTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.log = self.root / 'compose.log'
        self.counter = self.root / 'count'
        self.counter.write_text('0')
        docker = self.root / 'docker'
        docker.write_text('''#!/bin/sh
n=$(cat "$TEST_COUNTER"); n=$((n + 1)); echo "$n" > "$TEST_COUNTER"
case "$*" in
  "compose up"*) echo "up attempt $n"; [ "$n" -ge "$TEST_REQUIRED" ] && exit 0; exit 1;;
  "compose logs"*) echo "diagnostic logs";;
  "compose down"*) exit 0;;
esac
''')
        docker.chmod(0o700)
        self.env = dict(os.environ, PATH=str(self.root) + os.pathsep + os.environ['PATH'],
                        COMPOSE_UP_LOG=str(self.log), TEST_COUNTER=str(self.counter),
                        COMPOSE_UP_ATTEMPTS='3', COMPOSE_RETRY_DELAY='0')

    def run_script(self, required):
        return subprocess.run(['sh', str(ROOT / 'scripts/compose-up.sh')],
                              env=dict(self.env, TEST_REQUIRED=str(required)),
                              capture_output=True, text=True)

    def up_attempts(self):
        return self.log.read_text().count('up attempt')

    def test_retries_until_success(self):
        result = self.run_script(2)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, self.up_attempts())

    def test_exhausted_retries_fail_and_report_log(self):
        result = self.run_script(9)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('diagnostic logs', result.stderr)
        self.assertIn('failed after 3 attempts', result.stderr)
        self.assertEqual(3, self.up_attempts())


if __name__ == '__main__':
    unittest.main()
