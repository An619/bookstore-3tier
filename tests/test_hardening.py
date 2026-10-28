"""Availability, backup, manifest and release-engineering guard rails.

These are structural tests: Helm and kubeconform run in CI, so here we assert the
properties that a render alone would not protect (defaults, guards, the pinned
scanner, the SLO numbers used by the load test).
"""
import json
from pathlib import Path
import re
import shutil
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]
SERVICES = ('catalog-service', 'orders-service', 'inventory-service')
TEMPLATES = {
    'catalog-service': 'catalog_books',
    'orders-service': 'customer_orders',
    'inventory-service': 'inventory_items',
}


def read(path):
    return (ROOT / path).read_text()


def values(chart):
    return read(f'helm/{chart}/values.yaml')


class AvailabilityTest(unittest.TestCase):
    def test_every_workload_has_a_pod_disruption_budget(self):
        for chart in SERVICES + ('frontend',):
            with self.subTest(chart=chart):
                template = read(f'helm/{chart}/templates/pdb.yaml')
                self.assertIn('kind: PodDisruptionBudget', template)
                self.assertIn('policy/v1', template)
                self.assertIn('app.kubernetes.io/name:', template)
                # A PDB without a selector protects nothing.
                self.assertIn('selector:', template)
                # Enabled by default: a drain must not silently take everything down.
                self.assertIn('podDisruptionBudget:\n  enabled: true', values(chart))

    def test_pdb_scales_with_the_replica_count(self):
        template = read('helm/orders-service/templates/pdb.yaml')
        self.assertIn('gt (int .Values.replicaCount) 1', template)
        self.assertIn('minAvailable: 1', template)
        self.assertIn('maxUnavailable: 1', template)
        self.assertIn('podDisruptionBudget.minAvailable', template)
        self.assertIn('minAvailable: ""', values('orders-service'))

    def test_autoscaling_is_opt_in_and_replaces_the_fixed_replica_count(self):
        for chart in SERVICES + ('frontend',):
            with self.subTest(chart=chart):
                deployment = read(f'helm/{chart}/templates/deployment.yaml')
                self.assertIn('{{- if not .Values.autoscaling.enabled }}', deployment,
                              'the HPA and a fixed replicaCount would fight each other')
                hpa = read(f'helm/{chart}/templates/hpa.yaml')
                self.assertIn('autoscaling/v2', hpa)
                self.assertIn('averageUtilization', hpa)
                # The block may carry comments between the key and the value.
                self.assertRegex(values(chart), r'autoscaling:\n(  #.*\n)*  enabled: false')

    def test_hpa_targets_the_service_itself(self):
        hpa = read('helm/inventory-service/templates/hpa.yaml')
        self.assertIn('kind: Deployment', hpa)
        self.assertIn('name: {{ .Release.Name }}', hpa)
        self.assertIn('stabilizationWindowSeconds', hpa)


class BackupTest(unittest.TestCase):
    def test_backup_is_opt_in_and_needs_a_database_in_the_cluster(self):
        for chart in SERVICES:
            with self.subTest(chart=chart):
                template = read(f'helm/{chart}/templates/backup-cronjob.yaml')
                self.assertTrue(template.startswith('{{- if and .Values.backup.enabled .Values.database.deploy }}'),
                                'an external database must not render a dump job')
                self.assertIn('kind: CronJob', template)
                self.assertIn('backup:\n  # Opt-in CronJob', values(chart))
                self.assertIn('enabled: false', values(chart))

    def test_backup_proves_it_can_be_restored(self):
        for chart in SERVICES:
            with self.subTest(chart=chart):
                template = read(f'helm/{chart}/templates/backup-cronjob.yaml')
                self.assertIn('pg_dump', template)
                self.assertIn('gzip', template)
                # A dump nobody restored is a hope, not a backup.
                self.assertIn('gunzip -t', template)
                self.assertIn('createdb "$scratch"', template)
                self.assertIn('trap', template, 'the scratch database must be dropped')
                self.assertIn(
                    f"SELECT count(*) FROM {TEMPLATES[chart]}", template,
                    'the restore check must read a table this service owns')

    def test_backup_uses_the_right_endpoint_and_secret(self):
        for chart in SERVICES:
            with self.subTest(chart=chart):
                template = read(f'helm/{chart}/templates/backup-cronjob.yaml')
                self.assertIn('{{ .Values.database.host | quote }}', template)
                self.assertNotIn('value: 127.0.0.1', template,
                                 'the database is a separate pod, not a sidecar')
                # Orders/inventory name the Secret database.secretName, catalog
                # uses database.existingSecret: both must resolve to a real Secret.
                self.assertIn('database.secretName | default .Values.database.existingSecret', template)
                self.assertIn('key: username', template)
                self.assertIn('key: password', template)

    def test_backup_can_copy_archives_off_cluster(self):
        template = read('helm/orders-service/templates/backup-cronjob.yaml')
        self.assertIn('{{ .Values.backup.s3.image }}', template)
        self.assertIn('amazon/aws-cli', values('orders-service'),
                      'the uploader image must be pinned in values')
        self.assertIn('aws s3 cp', template)
        self.assertIn('/backup/.done', template, 'the uploader must wait for the dump')
        self.assertIn('if .Values.backup.s3.bucket', template)
        # No bucket configured means no S3 credentials are mounted.
        self.assertIn('automountServiceAccountToken: {{ if .Values.backup.s3.bucket }}', template)

    def test_backup_runs_unprivileged(self):
        template = read('helm/orders-service/templates/backup-cronjob.yaml')
        self.assertIn('allowPrivilegeEscalation: false', template)
        self.assertIn('drop: ["ALL"]', template)
        self.assertIn('restartPolicy: Never', template)
        self.assertIn('activeDeadlineSeconds', template)


class ExternalSecretsTest(unittest.TestCase):
    """The opt-in secret-manager path must name the Secrets the charts expect."""

    def test_synced_secret_names_match_the_charts(self):
        manifest = read('deploy/eks/external-secrets/external-secrets.yaml')
        targets = re.findall(r'name: ([a-z-]+-db-secret)', manifest)
        self.assertEqual({'catalog-db-secret', 'orders-db-secret', 'inventory-db-secret'}, set(targets))
        charts = {
            'catalog-service': re.search(r'existingSecret: (\S+)', values('catalog-service')).group(1),
            'orders-service': re.search(r'secretName: (\S+)', values('orders-service')).group(1),
            'inventory-service': re.search(r'secretName: (\S+)', values('inventory-service')).group(1),
        }
        self.assertEqual(set(targets), set(charts.values()),
                         'a synced Secret the chart does not reference is silently unused')

    def test_it_is_documented_as_opt_in_and_not_applied_automatically(self):
        readme = read('deploy/eks/external-secrets/README.md')
        self.assertIn('not** applied by `make eks-deploy`', readme)
        self.assertIn('IRSA', readme)
        eks = read('docs/eks.md')
        self.assertIn('external-secrets', eks)
        deploy = read('scripts/eks.sh')
        self.assertNotIn('external-secrets/external-secrets.yaml', deploy,
                         'the operator path stays manual: it needs an IAM binding')

    def test_no_secret_values_are_committed(self):
        for path in (ROOT / 'deploy/eks/external-secrets').glob('*.yaml'):
            text = path.read_text()
            self.assertNotRegex(text, r'password:\s*[^<\s"]+',
                                f'{path.name} must not carry a real password')


class ManifestValidationTest(unittest.TestCase):
    def test_script_validates_rendered_charts(self):
        script = read('scripts/validate-manifests.sh')
        self.assertIn('kubeconform', script)
        for chart in SERVICES + ('frontend', 'bookstore'):
            self.assertIn(f'for chart in frontend bookstore catalog-service orders-service inventory-service', script)
        # Chart features that only exist with --set must be exercised too.
        self.assertIn('--set backup.enabled=true', script)
        self.assertIn('--set autoscaling.enabled=true', script)
        self.assertIn('deploy/kubernetes/auth/keycloak.yaml', script)
        self.assertIn('monitoring/slo-rules.yaml', script)

    def test_external_secret_manifests_are_validated_in_ci(self):
        script = read('scripts/validate-manifests.sh')
        self.assertIn('external-secrets/external-secrets.yaml', script)
        self.assertIn('ClusterSecretStore', script)

    def test_script_reports_errors_as_annotations(self):
        script = read('scripts/validate-manifests.sh')
        self.assertIn('::error::', script)

    def test_script_fails_clearly_without_its_tools(self):
        result = subprocess.run(['sh', str(ROOT / 'scripts/validate-manifests.sh')],
                                cwd=ROOT, env={'PATH': '/usr/bin:/bin'}, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('is required', result.stderr)

    def test_ci_runs_the_validation(self):
        ci = read('.github/workflows/ci.yml')
        self.assertIn('sh scripts/validate-manifests.sh', ci)
        self.assertIn('kubeconform', ci)
        # Pinned download, so a new release cannot change the result silently.
        self.assertRegex(ci, r'version=v\d+\.\d+\.\d+')
        self.assertIn('kubeconform/releases/download/${version}', ci)
        self.assertIn('install -m 0755 /tmp/kubeconform', ci)

    def test_chart_checks_cover_the_new_templates(self):
        script = read('scripts/check-charts.sh')
        for expected in ('kind: PodDisruptionBudget', 'kind: HorizontalPodAutoscaler', 'kind: CronJob',
                         'amazon/aws-cli'):
            self.assertIn(expected, script, f'check-charts.sh must render {expected}')


class SecurityWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.workflow = read('.github/workflows/security.yml')

    def test_it_is_scheduled_and_manual_not_per_pr(self):
        self.assertIn('schedule:', self.workflow)
        self.assertIn('workflow_dispatch:', self.workflow)
        self.assertNotIn('pull_request:', self.workflow,
                         'upstream base-image CVEs must not fail unrelated pull requests')

    def test_scanner_is_pinned_and_ignores_unfixed_findings(self):
        self.assertIn('aquasec/trivy:0.57.1', self.workflow)
        self.assertIn('--ignore-unfixed', self.workflow)
        self.assertIn('--exit-code 1', self.workflow)

    def test_it_scans_all_four_images_and_emits_sboms(self):
        for image in ('catalog-service', 'orders-service', 'inventory-service', 'frontend'):
            self.assertIn(f'bookstore/{image}:scan', self.workflow)
        self.assertIn('--format cyclonedx', self.workflow)
        self.assertIn('security-reports', self.workflow)

    def test_the_severity_gate_is_selectable(self):
        self.assertIn("inputs['fail-on']", self.workflow)
        self.assertIn('CRITICAL', self.workflow)


class LoadTestConfigTest(unittest.TestCase):
    def test_thresholds_match_the_documented_slos(self):
        script = read('perf/checkout-load.js')
        self.assertIn("http_req_failed: ['rate<0.005']", script, 'availability SLO is 99.5%')
        self.assertIn("p(95)<1500", script, 'latency SLO is p95 < 1.5s')
        self.assertIn("checkout_confirmed: ['rate>=0.95']", script, 'checkout SLO is 95%')
        rules = read('monitoring/slo-rules.yaml')
        self.assertIn('0.995', rules)
        self.assertIn('1.5', rules)
        self.assertIn('0.95', rules)

    def test_it_measures_the_async_saga_not_just_202s(self):
        script = read('perf/checkout-load.js')
        self.assertIn('pollOrder', script)
        self.assertIn("status === 'CONFIRMED'", script)
        # Load tests must not leave stock reserved or orders behind.
        self.assertIn(':cleanup', script)
        self.assertIn('http.del(', script)

    def test_workflow_is_manual_or_nightly(self):
        workflow = read('.github/workflows/load.yml')
        self.assertIn('workflow_dispatch:', workflow)
        self.assertIn('schedule:', workflow)
        self.assertNotIn('pull_request:', workflow,
                         'runner CPU varies, so a load gate would make PRs flaky')
        self.assertIn('grafana/k6:0.54.0', workflow)
        self.assertIn('--check-only', workflow, 'the API must be ready before the test starts')


class DeliveryHygieneTest(unittest.TestCase):
    def test_renovate_config_is_valid_and_pins_actions(self):
        config = json.loads(read('renovate.json'))
        self.assertEqual(['dependencies'], config['labels'])
        rules = config['packageRules']
        actions = [rule for rule in rules if 'github-actions' in rule.get('matchManagers', [])]
        self.assertTrue(actions and actions[0]['pinDigests'],
                        'GitHub Actions must be pinned to a digest')
        base_images = [rule for rule in rules if 'dockerfile' in rule.get('matchManagers', [])]
        self.assertTrue(base_images, 'base image bumps need their own label/review')

    def test_codeowners_covers_the_paths_it_names(self):
        entries = [line.split()[0] for line in read('.github/CODEOWNERS').splitlines()
                   if line.strip() and not line.strip().startswith('#')]
        self.assertTrue(entries)
        for entry in entries:
            if entry == '*':
                continue
            self.assertTrue((ROOT / entry.lstrip('/')).exists(), f'CODEOWNERS names a missing path: {entry}')
        for required in ('/helm/', '/terraform/', '/contracts/', '/services/'):
            self.assertIn(required, entries)

    def test_verify_deploy_checks_the_disruption_budgets(self):
        script = read('scripts/verify-deploy.sh')
        self.assertIn('get poddisruptionbudgets', script)
        self.assertIn('check_disruption_budgets', script)

    def test_make_targets_expose_the_new_checks(self):
        makefile = read('Makefile')
        for target in ('validate-manifests:', 'perf:', 'backup-plan:'):
            self.assertIn(target, makefile, f'missing make target {target}')


if __name__ == '__main__':
    unittest.main()
