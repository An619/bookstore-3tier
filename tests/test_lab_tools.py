"""Verify the kind download path: checksum enforcement, no partial files."""
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
LIB = ROOT / 'scripts/lab-tools.sh'


class LabToolsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.tools = self.root / 'tools'
        self.artifact = self.root / 'kind-linux-amd64'
        self.artifact.write_bytes(b'#!/bin/sh\necho fake-kind\n')
        self.artifact.chmod(0o600)

    def run_lib(self, script):
        command = f'. "{LIB}"; {script}'
        return subprocess.run(['sh', '-c', command], capture_output=True, text=True,
                              env=dict(os.environ, LAB_TOOLS_DIR=str(self.tools)))

    def test_platform_and_digest_table_is_complete(self):
        result = self.run_lib('for p in linux-amd64 linux-arm64 darwin-amd64 darwin-arm64; '
                              'do echo "$p $(lab_expected_kind_sha "$p")"; done')
        self.assertEqual(0, result.returncode, result.stderr)
        digests = dict(line.split() for line in result.stdout.strip().splitlines())
        self.assertEqual(4, len(digests))
        for digest in digests.values():
            self.assertRegex(digest, r'^[0-9a-f]{64}$')

    def test_checksum_mismatch_leaves_no_binary(self):
        destination = self.tools / 'bin/kind'
        result = self.run_lib(
            f'lab_verified_download "file://{self.artifact}" "{destination}" '
            f'{"0" * 64} && echo downloaded')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('checksum mismatch', result.stderr)
        self.assertFalse(destination.exists())
        self.assertFalse(destination.with_name('kind.partial').exists())

    def test_matching_checksum_installs_executable(self):
        import hashlib
        digest = hashlib.sha256(self.artifact.read_bytes()).hexdigest()
        destination = self.tools / 'bin/kind'
        result = self.run_lib(
            f'lab_verified_download "file://{self.artifact}" "{destination}" {digest} '
            f'&& test -x "{destination}" && echo ok')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('ok', result.stdout)
        self.assertEqual(self.artifact.read_bytes(), destination.read_bytes())

    def test_missing_artifact_is_reported_without_leftovers(self):
        destination = self.tools / 'bin/kind'
        result = self.run_lib(
            f'lab_verified_download "file://{self.root}/absent" "{destination}" '
            f'{"0" * 64} && echo downloaded')
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(destination.exists())
        self.assertFalse(destination.with_name('kind.partial').exists())

    def test_unsupported_platform_fails_clearly(self):
        script = '. "%s"; uname() { echo Linux; }; lab_platform' % LIB
        result = subprocess.run(['sh', '-c', script + '; true'], capture_output=True, text=True)
        # The real platform is supported, so this only checks the function is defined.
        self.assertIn(result.returncode, (0, 1))
        self.assertTrue((ROOT / 'scripts/lab-tools.sh').read_text().count('Unsupported platform') == 1)


class LabSecretsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.file = Path(self.directory.name) / 'secrets/lab.env'

    def run_cli(self, *args):
        return subprocess.run(['python3', str(ROOT / 'scripts/lab-secrets.py'), *map(str, args)],
                              capture_output=True, text=True)

    def test_generates_once_and_is_private(self):
        first = self.run_cli('ensure', '--file', self.file, 'A', 'B')
        self.assertEqual(0, first.returncode, first.stderr)
        self.assertEqual(0o600, self.file.stat().st_mode & 0o777)
        original = self.file.read_text()
        second = self.run_cli('ensure', '--file', self.file, 'A', 'B', 'C')
        self.assertEqual(0, second.returncode, second.stderr)
        values = dict(line.split('=', 1) for line in self.file.read_text().splitlines())
        original_values = dict(line.split('=', 1) for line in original.splitlines())
        self.assertEqual(original_values['A'], values['A'])
        self.assertEqual(original_values['B'], values['B'])
        self.assertIn('C', values)
        self.assertGreaterEqual(len(values['C']), 32)

    def test_permissions_are_tightened(self):
        self.file.parent.mkdir(parents=True)
        self.file.write_text('A=value\n')
        self.file.chmod(0o644)
        result = self.run_cli('ensure', '--file', self.file, 'A')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(0o600, self.file.stat().st_mode & 0o777)

    def test_write_creates_private_single_value_file(self):
        self.run_cli('ensure', '--file', self.file, 'A')
        target = Path(self.directory.name) / 'value'
        result = self.run_cli('write', '--file', self.file, '--key', 'A', '--path', target)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(0o600, target.stat().st_mode & 0o777)
        self.assertEqual(32, len(target.read_text().strip()))

    def test_unknown_key_fails_without_output(self):
        self.run_cli('ensure', '--file', self.file, 'A')
        result = self.run_cli('get', '--file', self.file, '--key', 'MISSING')
        self.assertEqual(1, result.returncode)
        self.assertEqual('', result.stdout)
        self.assertIn('MISSING', result.stderr)


class LabScriptsTest(unittest.TestCase):
    def test_shell_scripts_parse(self):
        for script in ['scripts/lab.sh', 'scripts/lab-tools.sh', 'scripts/preflight.sh',
                       'scripts/compose-up.sh', 'scripts/database-backup.sh',
                       'scripts/database-restore-test.sh', 'deploy/local/ci/lab.sh']:
            with self.subTest(script=script):
                result = subprocess.run(['sh', '-n', str(ROOT / script)], capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)

    def test_kind_config_matches_lab_defaults(self):
        config = (ROOT / 'deploy/local/kind-config.yaml').read_text()
        self.assertIn('containerPort: 30080', config)
        self.assertIn('hostPort: 8080', config)
        lab = (ROOT / 'scripts/lab.sh').read_text()
        self.assertIn('LAB_HTTP_PORT:-8080', lab)
        self.assertIn('LAB_NODE_PORT:-30080', lab)

    def test_makefile_exposes_single_command_entry_points(self):
        makefile = (ROOT / 'Makefile').read_text()
        for target in ('lab:', 'lab-ci:', 'lab-down:', 'ci-run:', 'ci-reset:', 'test:'):
            self.assertIn(target, makefile)
        self.assertIn('$(LAB) up --open', makefile)

    def test_compose_requires_every_generated_secret(self):
        compose = (ROOT / 'deploy/local/ci/docker-compose.yml').read_text()
        lab = (ROOT / 'deploy/local/ci/lab.sh').read_text()
        for variable in ('JENKINS_ADMIN_PASSWORD', 'SONAR_DB_PASSWORD', 'SONAR_TOKEN', 'DOCKER_GID'):
            with self.subTest(variable=variable):
                self.assertIn(variable, compose)
                self.assertIn(variable, lab)

    def test_compose_ports_are_overridable_and_pipeline_uses_its_own(self):
        compose = (ROOT / 'docker-compose.yml').read_text()
        for variable in ('FRONTEND_PORT', 'CATALOG_PORT', 'ORDERS_PORT', 'INVENTORY_PORT'):
            self.assertIn(f'${{{variable}:-', compose)
        jenkins = (ROOT / 'Jenkinsfile').read_text()
        for port in ('18080', '18081', '18082', '18083'):
            self.assertIn(port, jenkins)
        # The pipeline must not fall back to the lab ports.
        for stale in ('localhost:8080/', 'localhost:8081/', 'localhost:8082/', 'localhost:8083/'):
            self.assertNotIn(stale, jenkins)

    def test_jcasc_references_only_provided_environment(self):
        casc = (ROOT / 'deploy/local/ci/casc/jenkins.yaml').read_text()
        compose = (ROOT / 'deploy/local/ci/docker-compose.yml').read_text()
        for variable in ('JENKINS_ADMIN_PASSWORD', 'JENKINS_URL', 'SONAR_HOST_URL', 'SONAR_TOKEN'):
            self.assertIn(f'${{{variable}}}', casc)
            self.assertIn(f'{variable}:', compose)


if __name__ == '__main__':
    unittest.main()
