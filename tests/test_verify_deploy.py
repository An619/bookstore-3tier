"""Exercise scripts/verify-deploy.sh with a fake kubectl.

The script is what a human runs after `make lab` or `make eks-deploy` to answer
"did it actually work?", so its pass/fail logic has to be right even though this
sandbox has no cluster.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

# Responds from a JSON fixture so each test can shape the cluster state.
FAKE_KUBECTL = '''#!/bin/sh
echo "kubectl $*" >> "$VERIFY_TEST_LOG"
case "$*" in
  *"get service bookstore-frontend"*jsonpath*)
    [ -n "${VERIFY_TEST_NO_LB:-}" ] && exit 0
    printf '%s' "${VERIFY_TEST_LB:-k8s-bookstore-frontend-123.ap-south-1.elb.amazonaws.com}"
    ;;
  *"get pods -l"*)
    echo "orders-service-6c8-def   1/1   Running   0   5m"
    ;;
  "get pods -o json"*|*"get pods -o json"*)
    if [ -n "${VERIFY_TEST_PODS:-}" ]; then cat "$VERIFY_TEST_PODS"; else echo '{"items": []}'; fi
    ;;
  *"get pod "*)
    [ -n "${VERIFY_TEST_MISSING_DB:-}" ] && exit 1
    exit 0
    ;;
  *" exec "*)
    printf '%s\\n' "${VERIFY_TEST_HISTORY:-1
2}"
    ;;
  *"delete pod"*)
    [ -n "${VERIFY_TEST_DELETE_FAILS:-}" ] && exit 1
    exit 0
    ;;
esac
exit 0
'''


def pods(*entries):
    """Build the pod list kubectl would return."""
    items = []
    for name, phase, ready, restarts, waiting in entries:
        state = {'running': {}} if not waiting else {'waiting': {'reason': waiting}}
        items.append({
            'metadata': {'name': name},
            'status': {
                'phase': phase,
                'containerStatuses': [
                    {'name': 'app', 'ready': ready, 'restartCount': restarts, 'state': state}
                ],
            },
        })
    return {'items': items}


HEALTHY = pods(
    ('catalog-service-7d9-abc', 'Running', True, 0, None),
    ('orders-service-6c8-def', 'Running', True, 0, None),
    ('inventory-service-5b7-ghi', 'Running', True, 0, None),
    ('bookstore-frontend-4a6-jkl', 'Running', True, 0, None),
    ('catalog-service-postgres-0', 'Running', True, 0, None),
)


class VerifyDeployTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.log = self.root / 'calls.log'
        self.log.touch()
        kubectl = self.bin / 'kubectl'
        kubectl.write_text(FAKE_KUBECTL)
        kubectl.chmod(0o755)
        self.pods_file = self.root / 'pods.json'
        self.set_pods(HEALTHY)
        self.env = dict(os.environ, PATH=f'{self.bin}:/usr/bin:/bin',
                        VERIFY_TEST_LOG=str(self.log),
                        VERIFY_TEST_PODS=str(self.pods_file))

    def set_pods(self, payload):
        self.pods_file.write_text(json.dumps(payload))

    def calls(self):
        return [line for line in self.log.read_text().splitlines() if line.strip()]

    @staticmethod
    def output(result):
        """The script reports some findings on stdout and hard failures on stderr."""
        return result.stdout + result.stderr

    def run_verify(self, *args, **env):
        return subprocess.run(['sh', str(ROOT / 'scripts/verify-deploy.sh'), *args],
                              cwd=ROOT, env=dict(self.env, **env),
                              capture_output=True, text=True)

    def test_successful_run_reports_every_check(self):
        result = self.run_verify('--skip-smoke')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('PASS: deployment verified', result.stdout)
        self.assertIn('migrations', result.stdout)
        calls = '\n'.join(self.calls())
        for workload in ('catalog-service', 'orders-service', 'inventory-service', 'bookstore-frontend'):
            self.assertIn(f'rollout status deployment/{workload}', calls)
        # Flyway is checked in all three databases.
        self.assertEqual(3, calls.count('exec'))

    def test_crash_loop_fails_the_run(self):
        self.set_pods(pods(('orders-service-6c8-def', 'Running', False, 0, 'CrashLoopBackOff')))
        result = self.run_verify('--skip-smoke')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('CrashLoopBackOff', self.output(result))
        self.assertIn('FAILED:', result.stderr)

    def test_restarts_above_the_threshold_fail(self):
        self.set_pods(pods(('orders-service-6c8-def', 'Running', True, 3, None)))
        result = self.run_verify('--skip-smoke')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('restarted 3 times', self.output(result))

        result = self.run_verify('--skip-smoke', '--max-restarts', '3')
        self.assertEqual(0, result.returncode, result.stderr)

    def test_pending_pod_fails(self):
        self.set_pods(pods(('orders-service-6c8-def', 'Pending', False, 0, None)))
        result = self.run_verify('--skip-smoke')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('is Pending', self.output(result))

    def test_migration_drift_fails(self):
        result = self.run_verify('--skip-smoke', VERIFY_TEST_HISTORY='1\n2\n3')
        self.assertNotEqual(0, result.returncode)
        self.assertIn("reports Flyway version '3'", self.output(result))

        result = self.run_verify('--skip-smoke', VERIFY_TEST_HISTORY='1')
        self.assertNotEqual(0, result.returncode)
        self.assertIn("reports Flyway version '1'", self.output(result))

    def test_external_database_is_skipped_not_failed(self):
        result = self.run_verify('--skip-smoke', VERIFY_TEST_MISSING_DB='1')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('externally managed database', result.stdout)
        self.assertNotIn('exec', '\n'.join(self.calls()))

    def test_smoke_failure_fails_the_run(self):
        # No server is listening on the port, so the smoke step must fail cleanly.
        result = self.run_verify('--timeout', '1',
                                 VERIFY_URL='http://127.0.0.1:9')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('the smoke test failed', self.output(result))

    def test_resilience_kills_a_pod_then_rechecks(self):
        result = self.run_verify('--skip-smoke', '--resilience')
        self.assertEqual(0, result.returncode, result.stderr)
        calls = '\n'.join(self.calls())
        self.assertIn('delete pod -l app.kubernetes.io/name=orders-service', calls)
        self.assertIn('rollout status deployment/orders-service', calls)
        self.assertIn('recovered a replacement pod', result.stdout)

    def test_resilience_failure_is_reported(self):
        result = self.run_verify('--skip-smoke', '--resilience', VERIFY_TEST_DELETE_FAILS='1')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('could not delete', self.output(result))

    def test_unknown_option_is_rejected(self):
        result = self.run_verify('--nope')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('unknown option', result.stderr)

    def test_requires_kubectl(self):
        # Every self-hosted and GitHub image ships kubectl in a different place, so
        # hide it by handing the script a PATH with only what it needs to start.
        only = self.root / 'only'
        only.mkdir(exist_ok=True)
        dirname = shutil.which('dirname')
        self.assertIsNotNone(dirname)
        (only / 'dirname').symlink_to(dirname)
        result = subprocess.run(['/bin/sh', str(ROOT / 'scripts/verify-deploy.sh'), '--skip-smoke'],
                                cwd=ROOT, env=dict(os.environ, PATH=str(only)),
                                capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('kubectl is required', result.stderr)


class EksVerifyTest(unittest.TestCase):
    """`sh scripts/eks.sh verify` must resolve the load balancer and delegate."""

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.log = self.root / 'calls.log'
        self.log.touch()
        kubectl = self.bin / 'kubectl'
        kubectl.write_text(FAKE_KUBECTL)
        kubectl.chmod(0o755)
        aws = self.bin / 'aws'
        aws.write_text('#!/bin/sh\necho "aws $*" >> "$VERIFY_TEST_LOG"\nexit 0\n')
        aws.chmod(0o755)
        self.pods_file = self.root / 'pods.json'
        self.pods_file.write_text(json.dumps(HEALTHY))
        self.env = dict(os.environ, PATH=f'{self.bin}:/usr/bin:/bin',
                        VERIFY_TEST_LOG=str(self.log),
                        VERIFY_TEST_PODS=str(self.pods_file),
                        EKS_VERIFY_ARGS='--skip-smoke')

    def run_eks(self, action):
        return subprocess.run(['sh', str(ROOT / 'scripts/eks.sh'), action], cwd=ROOT,
                              env=self.env, capture_output=True, text=True)

    def test_verify_targets_the_load_balancer_address(self):
        result = self.run_eks('verify')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('k8s-bookstore-frontend-123.ap-south-1.elb.amazonaws.com', result.stdout)
        self.assertIn('PASS: deployment verified', result.stdout)

    def test_verify_fails_clearly_without_an_address(self):
        self.env['VERIFY_TEST_NO_LB'] = '1'
        # Keep the wait short: the script polls for up to ten minutes.
        result = subprocess.run(['sh', str(ROOT / 'scripts/eks.sh'), 'verify'], cwd=ROOT,
                                env=dict(self.env, EKS_LB_WAIT_ATTEMPTS='1'),
                                capture_output=True, text=True, timeout=120)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('no load-balancer address', result.stderr)


class ClusterAuthTest(unittest.TestCase):
    """`auth-cluster.sh` deploys the same realm the Compose profile uses."""

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.log = self.root / 'calls.log'
        self.log.touch()
        kubectl = self.bin / 'kubectl'
        kubectl.write_text('#!/bin/sh\necho "kubectl $*" >> "$AUTH_TEST_LOG"\nexit 0\n')
        kubectl.chmod(0o755)
        helm = self.bin / 'helm'
        helm.write_text('#!/bin/sh\necho "helm $*" >> "$AUTH_TEST_LOG"\nexit 0\n')
        helm.chmod(0o755)
        self.env = dict(os.environ, PATH=f'{self.bin}:/usr/bin:/bin',
                        AUTH_TEST_LOG=str(self.log),
                        LAB_TOOLS_DIR=str(self.root / 'tools'))

    def run_auth(self, action, **env):
        return subprocess.run(['sh', str(ROOT / 'scripts/auth-cluster.sh'), action],
                              cwd=ROOT, env=dict(self.env, **env),
                              capture_output=True, text=True)

    def calls(self):
        return self.log.read_text()

    def test_start_imports_the_shared_realm_and_keeps_the_password_out_of_argv(self):
        result = self.run_auth('start')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        calls = self.calls()
        self.assertIn('create configmap bookstore-realm', calls)
        self.assertIn('bookstore-realm.json=deploy/local/auth/bookstore-realm.json', calls)
        self.assertIn('create secret generic keycloak-admin', calls)
        self.assertIn('apply -f deploy/kubernetes/auth/keycloak.yaml', calls)
        self.assertIn('rollout status deployment/keycloak', calls)
        # The generated admin password lives in .tools, never in a manifest.
        secrets = (self.root / 'tools/eks/secrets.env')
        self.assertTrue(secrets.exists(), 'the admin password must be persisted in .tools')
        self.assertNotIn('password=', (ROOT / 'deploy/kubernetes/auth/keycloak.yaml').read_text())

    def test_secure_turns_enforcement_on_with_the_in_cluster_jwks(self):
        result = self.run_auth('secure')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        calls = self.calls()
        self.assertIn('security.jwt.enabled=true', calls)
        self.assertIn('http://keycloak:8080/realms/bookstore/protocol/openid-connect/certs', calls)
        for workload in ('orders-service', 'inventory-service'):
            self.assertIn(f'helm upgrade --install {workload} helm/{workload}', calls)

    def test_secure_honours_a_helm_release_prefix(self):
        result = self.run_auth('secure', AUTH_RELEASE_PREFIX='bookstore-lab-')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('helm upgrade --install bookstore-lab-orders-service', self.calls())

    def test_insecure_restores_the_lab_default(self):
        result = self.run_auth('insecure')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('security.jwt.enabled=false', self.calls())

    def test_stop_removes_only_what_it_created(self):
        result = self.run_auth('stop')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        calls = self.calls()
        for resource in ('deployment keycloak', 'service keycloak', 'configmap bookstore-realm',
                         'secret keycloak-admin'):
            self.assertIn(f'delete {resource}', calls)
        self.assertNotIn('delete deployment orders-service', calls)

    def test_start_refuses_without_the_namespace(self):
        kubectl = self.bin / 'kubectl'
        kubectl.write_text('#!/bin/sh\necho "kubectl $*" >> "$AUTH_TEST_LOG"\n'
                           'case "$*" in\n *"get namespace"*) exit 1 ;; esac\nexit 0\n')
        kubectl.chmod(0o755)
        result = self.run_auth('start')
        self.assertNotEqual(0, result.returncode)
        self.assertIn("'bookstore' does not exist", result.stderr)

    def test_unknown_action_is_rejected(self):
        result = self.run_auth('deploy')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('unknown action', result.stderr)


class WireUpTest(unittest.TestCase):
    def test_makefile_and_docs_reference_the_script(self):
        makefile = (ROOT / 'Makefile').read_text()
        self.assertIn('lab-verify:', makefile)
        self.assertIn('eks-verify:', makefile)
        self.assertIn('scripts/verify-deploy.sh', makefile)
        self.assertIn('auth-cluster:', makefile)
        for doc in ('docs/QUICKSTART.md', 'docs/killercoda.md', 'docs/eks.md'):
            self.assertIn('verify-deploy.sh', (ROOT / doc).read_text(), f'{doc} must mention the verification')

    def test_cluster_auth_manifest_is_lab_only_and_production_shaped(self):
        manifest = (ROOT / 'deploy/kubernetes/auth/keycloak.yaml').read_text()
        self.assertIn('start-dev', manifest)
        self.assertIn('LAB ONLY', manifest)
        self.assertIn('bookstore-realm', manifest)
        self.assertIn('runAsNonRoot: true', manifest)
        self.assertIn('drop: ["ALL"]', manifest)
        self.assertIn('/realms/bookstore', manifest, 'readiness must wait for the realm import')
        # The realm definition has exactly one source of truth.
        self.assertNotIn('"username": "demo"', manifest)

    def test_docs_explain_the_in_cluster_identity_provider(self):
        for doc in ('docs/microservices-delivery.md', 'docs/killercoda.md'):
            text = (ROOT / doc).read_text()
            self.assertIn('auth-cluster.sh', text, f'{doc} must document the in-cluster IdP')

    def test_smoke_test_has_a_read_only_mode(self):
        script = (ROOT / 'scripts/smoke-test.py').read_text()
        self.assertIn('--check-only', script)
        self.assertIn('def readiness_only', script)


if __name__ == '__main__':
    unittest.main()
