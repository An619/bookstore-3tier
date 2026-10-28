"""Simulate scripts/lab.sh with fake docker/kind/curl to check the orchestration.

The real lab needs Docker and a cluster; this verifies command order, image and
release names, secret handling and the smoke test invocation.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

FAKE_KIND = '''#!/bin/sh
echo "kind $*" >> "$LAB_TEST_LOG"
state="$LAB_TEST_STATE"
case "$1 $2" in
  "get clusters") [ -f "$state" ] && cat "$state"; exit 0 ;;
  "create cluster")
    for arg in "$@"; do
      [ "$previous" = "--name" ] && echo "$arg" > "$state"
      previous=$arg
    done
    # kind writes the kubeconfig when KUBECONFIG is set.
    [ -n "${KUBECONFIG:-}" ] && : > "$KUBECONFIG"
    exit 0 ;;
  "load docker-image") exit 0 ;;
  "export kubeconfig") [ -n "${KUBECONFIG:-}" ] && : > "$KUBECONFIG"; exit 0 ;;
  "delete cluster") rm -f "$state"; exit 0 ;;
esac
exit 0
'''

FAKE_DOCKER = '''#!/bin/sh
echo "docker $*" >> "$LAB_TEST_LOG"
if [ "$1 $2" = "info " ]; then exit 0; fi
if [ "$1 $2" = "compose version" ]; then echo "Docker Compose v2"; exit 0; fi
exit 0
'''

# Delegates everything except the smoke test to the real interpreter.
FAKE_PYTHON = '''#!/bin/sh
for arg in "$@"; do
  case "$arg" in
    */smoke-test.py|smoke-test.py)
      echo "smoke $*" >> "$LAB_TEST_LOG"
      echo "PASS: simulated"
      exit 0 ;;
  esac
done
exec "@REAL_PYTHON@" "$@"
'''


class LabFlowTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.log = self.root / 'calls.log'
        self.log.touch()
        real_python = shutil.which('python3')
        for name, body in (('kind', FAKE_KIND), ('docker', FAKE_DOCKER),
                           ('python3', FAKE_PYTHON.replace('@REAL_PYTHON@', real_python))):
            path = self.bin / name
            path.write_text(body)
            path.chmod(0o755)
        # Keep the fake python3 out of the way of locating the real one.
        self.env = dict(os.environ, PATH=f'{self.bin}:/usr/bin:/bin',
                        LAB_TEST_LOG=str(self.log),
                        LAB_TEST_STATE=str(self.root / 'clusters'),
                        LAB_TOOLS_DIR=str(self.root / 'tools'),
                        LAB_SECRETS_FILE=str(self.root / 'secrets/lab.env'))
        self.env.pop('LAB_KUBECONFIG', None)

    def calls(self):
        return self.log.read_text().splitlines()

    def run_lab(self, *args):
        return subprocess.run(['sh', str(ROOT / 'scripts/lab.sh'), *args],
                              cwd=ROOT, env=self.env, capture_output=True, text=True)

    def test_up_builds_deploys_and_smoke_tests(self):
        result = self.run_lab('up', '--no-open')
        self.assertEqual(0, result.returncode, result.stderr)
        calls = self.calls()
        joined = '\n'.join(calls)
        self.assertIn('kind create cluster --name bookstore-lab', joined)
        for image in ('bookstore-frontend', 'bookstore-catalog-service',
                      'bookstore-orders-service', 'bookstore-inventory-service'):
            self.assertIn(f'docker build -q -t {image}:local', joined)
        self.assertIn('kind load docker-image --name bookstore-lab', joined)
        # Three databases get chart-created Secrets from private password files.
        for component, key in (('catalog-service', 'CATALOG_DB_PASSWORD'),
                               ('orders-service', 'ORDERS_DB_PASSWORD'),
                               ('inventory-service', 'INVENTORY_DB_PASSWORD')):
            self.assertIn(f'upgrade --install {component} helm/{component}', joined)
            self.assertIn('--set database.createSecret=true', joined)
            self.assertIn(f'--set-file database.password={self.root}/tools/secrets/{key}', joined)
        self.assertIn('upgrade --install bookstore-frontend helm/frontend', joined)
        self.assertIn('--set service.type=NodePort', joined)
        self.assertIn('--set service.nodePort=30080', joined)
        self.assertIn('smoke', joined)
        self.assertIn('--base-url http://127.0.0.1:8080', joined)

        # Passwords exist only as 0600 files and in an ignored secrets file.
        secret_files = list((self.root / 'tools/secrets').iterdir())
        self.assertEqual(3, len(secret_files))
        for path in secret_files:
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            self.assertGreater(len(path.read_text()), 20)
        self.assertEqual(0o600, (self.root / 'secrets/lab.env').stat().st_mode & 0o777)
        # No password appears in the recorded command line.
        lab_env = (self.root / 'secrets/lab.env').read_text()
        for line in lab_env.splitlines():
            value = line.split('=', 1)[1]
            self.assertNotIn(value, joined)

    def test_rerun_reuses_cluster_and_secrets(self):
        self.assertEqual(0, self.run_lab('up', '--no-open').returncode)
        first = (self.root / 'secrets/lab.env').read_text()
        self.log.write_text('')
        result = self.run_lab('up', '--no-open', '--skip-build')
        self.assertEqual(0, result.returncode, result.stderr)
        joined = '\n'.join(self.calls())
        self.assertNotIn('create cluster', joined)
        self.assertNotIn('docker build', joined)
        self.assertIn('helm', joined)
        self.assertEqual(first, (self.root / 'secrets/lab.env').read_text())

    def test_port_override_changes_kind_mapping_and_nodeport(self):
        result = self.run_lab('up', '--no-open', '--skip-build', '--port', '8090')
        self.assertEqual(0, result.returncode, result.stderr)
        generated = (self.root / 'tools/kind-config.yaml').read_text()
        self.assertIn('hostPort: 8090', generated)
        self.assertIn('--set service.nodePort=30080', '\n'.join(self.calls()))
        self.assertIn('--base-url http://127.0.0.1:8090', '\n'.join(self.calls()))

    def test_down_deletes_the_cluster_and_local_secrets(self):
        self.assertEqual(0, self.run_lab('up', '--no-open').returncode)
        self.assertTrue((self.root / 'tools/secrets').exists())
        result = self.run_lab('down')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse((self.root / 'tools/secrets').exists())

    def test_unknown_action_is_rejected(self):
        result = self.run_lab('explode')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Unknown action', result.stderr)


if __name__ == '__main__':
    unittest.main()
