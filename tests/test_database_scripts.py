"""Exercise shell safety and cleanup with a fake Docker CLI, not a database."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class DatabaseScriptsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.backups = self.root / 'backups'
        docker = self.root / 'docker'
        docker.write_text('''#!/bin/sh
printf '%s\\n' "$*" >> "$DOCKER_TEST_LOG"
case "$*" in
  *pg_dump*) printf 'fake-custom-dump';;
  *pg_restore*) cat >/dev/null; test "${FAIL_RESTORE:-0}" = 0;;
esac
''')
        docker.chmod(0o700)
        self.log = self.root / 'docker.log'
        self.env = dict(os.environ, PATH=str(self.root) + os.pathsep + os.environ['PATH'],
                        DOCKER_TEST_LOG=str(self.log))

    def run_script(self, name, **env):
        return subprocess.run(['sh', str(ROOT / 'scripts' / name), str(self.backups)],
                              env=dict(self.env, **env), capture_output=True, text=True)

    def test_backup_all_services_and_refuse_overwrite(self):
        result = self.run_script('database-backup.sh')
        self.assertEqual(0, result.returncode, result.stderr)
        for service in ('catalog', 'orders', 'inventory'):
            file = self.backups / f'{service}.dump'
            self.assertTrue(file.stat().st_size > 0)
            self.assertEqual(0o600, file.stat().st_mode & 0o777)
        count = len(self.log.read_text().splitlines())
        self.assertNotEqual(0, self.run_script('database-backup.sh').returncode)
        self.assertEqual(count, len(self.log.read_text().splitlines()))

    def test_missing_backup_never_calls_docker(self):
        self.assertNotEqual(0, self.run_script('database-restore-test.sh').returncode)
        self.assertFalse(self.log.exists())

    def test_restore_uses_new_databases_and_cleans_up(self):
        self.assertEqual(0, self.run_script('database-backup.sh').returncode)
        result = self.run_script('database-restore-test.sh')
        self.assertEqual(0, result.returncode, result.stderr)
        lines = self.log.read_text().splitlines()
        self.assertEqual(3, sum('exec createdb' in line for line in lines))
        self.assertEqual(3, sum('exec dropdb' in line for line in lines))
        self.assertTrue(all('restore_verify_' in line for line in lines if 'exec pg_restore' in line))
        self.assertFalse(any('--clean' in line for line in lines))

    def test_failed_restore_runs_cleanup_and_stops(self):
        self.assertEqual(0, self.run_script('database-backup.sh').returncode)
        result = self.run_script('database-restore-test.sh', FAIL_RESTORE='1')
        self.assertNotEqual(0, result.returncode)
        lines = self.log.read_text().splitlines()
        self.assertEqual(1, sum('exec createdb' in line for line in lines))
        self.assertEqual(1, sum('exec dropdb' in line for line in lines))


if __name__ == '__main__':
    unittest.main()
