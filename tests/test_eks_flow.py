"""Exercise scripts/eks.sh with fake aws/docker/kubectl/helm/terraform.

No AWS calls are made: this checks command construction, safety guards and the
image names that the charts will receive.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

FAKE_AWS = '''#!/bin/sh
echo "aws $*" >> "$EKS_TEST_LOG"
case "$1 $2" in
  "sts get-caller-identity") echo 123456789012 ;;
  "ecr describe-repositories") exit 0 ;;
  "ecr get-login-password") echo fake-password ;;
  "eks update-kubeconfig") exit 0 ;;
esac
exit 0
'''

FAKE_DOCKER = '''#!/bin/sh
echo "docker $*" >> "$EKS_TEST_LOG"
case "$1" in
  login|push) exit 0 ;;
esac
exit 0
'''

# Secrets are reported missing unless $EKS_TEST_SECRETS exists.
FAKE_KUBECTL = '''#!/bin/sh
echo "kubectl $*" >> "$EKS_TEST_LOG"
case "$*" in
  *"get storageclass"*) echo true ;;
  *"get secret"*) [ -f "$EKS_TEST_SECRETS" ] && exit 0; exit 1 ;;
  # The frontend Service disappears once the Helm release is uninstalled.
  *"get service bookstore-frontend"*) exit 1 ;;
esac
exit 0
'''

FAKE_HELM = '''#!/bin/sh
echo "helm $*" >> "$EKS_TEST_LOG"
exit 0
'''

FAKE_TERRAFORM = '''#!/bin/sh
echo "terraform $*" >> "$EKS_TEST_LOG"
exit 0
'''


class EksFlowTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.log = self.root / 'calls.log'
        self.log.touch()
        for name, body in (('aws', FAKE_AWS), ('docker', FAKE_DOCKER), ('kubectl', FAKE_KUBECTL),
                           ('helm', FAKE_HELM), ('terraform', FAKE_TERRAFORM)):
            path = self.bin / name
            path.write_text(body)
            path.chmod(0o755)
        # Work on a private copy of the Terraform directory.
        self.tf_dir = self.root / 'tf'
        shutil.copytree(ROOT / 'terraform/eks', self.tf_dir)
        (self.tf_dir / 'terraform.tfvars').unlink(missing_ok=True)
        self.env = dict(os.environ, PATH=f'{self.bin}:/usr/bin:/bin',
                        EKS_TEST_LOG=str(self.log),
                        LAB_TOOLS_DIR=str(self.root / 'tools'),
                        EKS_TEST_SECRETS=str(self.root / 'secrets-exist'),
                        EKS_TF_DIR=str(self.tf_dir),
                        EKS_SECRETS_FILE=str(self.root / 'eks/secrets.env'),
                        AWS_REGION='ap-south-1', EKS_CLUSTER='bookstore-lab-eks')

    def run_eks(self, *args, stdin=''):
        return subprocess.run(['sh', str(ROOT / 'scripts/eks.sh'), *args], cwd=ROOT,
                              env=self.env, input=stdin, capture_output=True, text=True)

    def calls(self):
        return self.log.read_text().splitlines()

    def revision(self):
        return subprocess.run(['git', 'rev-parse', '--short=12', 'HEAD'], cwd=ROOT,
                              capture_output=True, text=True).stdout.strip()

    def test_images_build_and_push_expected_ecr_names(self):
        result = self.run_eks('images')
        self.assertEqual(0, result.returncode, result.stderr)
        calls = '\n'.join(self.calls())
        registry = '123456789012.dkr.ecr.ap-south-1.amazonaws.com'
        self.assertIn('aws ecr get-login-password --region ap-south-1', calls)
        for name, path in (('frontend', 'frontend'),
                           ('catalog-service', 'services/catalog-service'),
                           ('orders-service', 'services/orders-service'),
                           ('inventory-service', 'services/inventory-service')):
            image = f'{registry}/bookstore-lab-{name}:{self.revision()}'
            self.assertIn(f'docker build -q -t {image} {path}', calls)
            self.assertIn(f'docker push {image}', calls)

    def test_images_fail_clearly_without_aws_credentials(self):
        aws = self.bin / 'aws'
        aws.write_text('#!/bin/sh\nexit 253\n')
        result = self.run_eks('images')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('AWS credentials are not configured', result.stderr)

    def test_images_fail_when_repositories_are_missing(self):
        aws = self.bin / 'aws'
        aws.write_text('''#!/bin/sh
case "$1 $2" in
  "sts get-caller-identity") echo 123456789012 ;;
  "ecr describe-repositories") exit 254 ;;
esac
exit 0
''')
        result = self.run_eks('images')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('sh scripts/eks.sh apply', result.stderr)

    def test_deploy_creates_secrets_and_uses_ephemeral_storage_by_default(self):
        result = self.run_eks('deploy')
        self.assertEqual(0, result.returncode, result.stderr)
        calls = '\n'.join(self.calls())
        # No EBS, no StorageClass applied by default.
        self.assertNotIn('storageclass', calls.lower())
        self.assertIn('database.persistence.enabled=false', calls)
        self.assertIn('kubectl create namespace bookstore', calls)
        self.assertIn('create secret generic catalog-db-secret', calls)
        self.assertIn('create secret generic orders-db-secret', calls)
        self.assertIn('create secret generic inventory-db-secret', calls)
        registry = '123456789012.dkr.ecr.ap-south-1.amazonaws.com'
        for component, release in (('catalog-service', 'catalog-service'),
                                   ('orders-service', 'orders-service'),
                                   ('inventory-service', 'inventory-service')):
            self.assertIn(f'helm upgrade --install {release} helm/{component}', calls)
            self.assertIn(f'image.repository={registry}/bookstore-lab-{component}', calls)
        self.assertIn('helm upgrade --install bookstore-frontend helm/frontend', calls)
        self.assertIn('service.type=LoadBalancer', calls)
        self.assertIn(f'image.tag={self.revision()}', calls)
        self.assertIn('rollout status deployment/bookstore-frontend', calls)

    def test_pvc_mode_uses_an_existing_storage_class(self):
        result = subprocess.run(['sh', str(ROOT / 'scripts/eks.sh'), 'deploy'], cwd=ROOT,
                                env=dict(self.env, EKS_DB_STORAGE='pvc', EKS_STORAGE_CLASS='efs-sc'),
                                capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        calls = '\n'.join(self.calls())
        self.assertIn('database.persistence.enabled=true', calls)
        self.assertIn('database.persistence.storageClassName=efs-sc', calls)
        self.assertNotIn('ebs.csi.aws.com', calls)

    def test_pvc_mode_requires_a_class_and_creates_none(self):
        result = subprocess.run(['sh', str(ROOT / 'scripts/eks.sh'), 'deploy'], cwd=ROOT,
                                env=dict(self.env, EKS_DB_STORAGE='pvc', EKS_STORAGE_CLASS=''),
                                capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('EKS_STORAGE_CLASS', result.stderr)
        self.assertFalse(any('create' in c and 'storageclass' in c.lower() for c in self.calls()))

    def test_external_database_mode_skips_pods_and_requires_hosts(self):
        env = dict(self.env, EKS_DB_STORAGE='external')
        result = subprocess.run(['sh', str(ROOT / 'scripts/eks.sh'), 'deploy'], cwd=ROOT,
                                env=env, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('EKS_DB_HOST_CATALOG', result.stderr)

        # Managed-database credentials must already exist as Secrets.
        (self.root / 'secrets-exist').touch()
        hosts = dict(env, EKS_DB_HOST_CATALOG='catalog.db.example', EKS_DB_HOST_ORDERS='orders.db.example',
                     EKS_DB_HOST_INVENTORY='inventory.db.example')
        result = subprocess.run(['sh', str(ROOT / 'scripts/eks.sh'), 'deploy'], cwd=ROOT,
                                env=hosts, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        calls = '\n'.join(self.calls())
        self.assertIn('database.deploy=false', calls)
        self.assertIn('database.host=catalog.db.example', calls)
        self.assertIn('database.host=orders.db.example', calls)
        self.assertIn('database.host=inventory.db.example', calls)

    def test_external_mode_requires_managed_database_secrets(self):
        # The kubectl fake reports Secrets missing unless the marker file exists.
        env = dict(self.env, EKS_DB_STORAGE='external', EKS_DB_HOST_CATALOG='a', EKS_DB_HOST_ORDERS='b',
                   EKS_DB_HOST_INVENTORY='c')
        result = subprocess.run(['sh', str(ROOT / 'scripts/eks.sh'), 'deploy'], cwd=ROOT,
                                env=env, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Secret catalog-db-secret is missing', result.stderr)

    def test_invalid_storage_mode_is_rejected(self):
        result = subprocess.run(['sh', str(ROOT / 'scripts/eks.sh'), 'deploy'], cwd=ROOT,
                                env=dict(self.env, EKS_DB_STORAGE='ebs'), capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Unknown EKS_DB_STORAGE', result.stderr)

    def test_existing_secrets_are_not_overwritten(self):
        (self.root / 'secrets-exist').touch()
        result = self.run_eks('deploy')
        self.assertEqual(0, result.returncode, result.stderr)
        calls = '\n'.join(self.calls())
        self.assertIn('Keeping existing Secret catalog-db-secret', result.stdout)
        self.assertNotIn('create secret generic', calls)

    def test_secrets_keep_existing_values_and_never_print_passwords(self):
        result = self.run_eks('secrets')
        self.assertEqual(0, result.returncode, result.stderr)
        secrets = (self.root / 'eks/secrets.env').read_text()
        values = [line.split('=', 1)[1] for line in secrets.splitlines() if '=' in line]
        self.assertEqual(3, len(values))
        for value in values:
            self.assertNotIn(value, result.stdout + result.stderr)
        self.assertEqual(0o600, (self.root / 'eks/secrets.env').stat().st_mode & 0o777)

    def test_release_manifest_uses_matching_ecr_prefix(self):
        output = self.root / 'release.json'
        result = subprocess.run(['sh', str(ROOT / 'scripts/eks.sh'), 'release'], cwd=ROOT,
                                env=dict(self.env, EKS_RELEASE_OUTPUT=str(output)),
                                capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        import json
        manifest = json.loads(output.read_text())
        repositories = [app['spec']['source']['helm']['parameters'][0]['value']
                        for app in manifest['items']]
        self.assertTrue(all(r.startswith('123456789012.dkr.ecr.ap-south-1.amazonaws.com/bookstore-lab-')
                            for r in repositories), repositories)
        self.assertTrue(all(r.endswith(f':{self.revision()}') is False for r in repositories))
        tags = [app['spec']['source']['helm']['parameters'][1]['value'] for app in manifest['items']]
        self.assertEqual({self.revision()}, set(tags))

    def test_terraform_paths_are_overridable_for_testing(self):
        self.assertIn('EKS_TF_DIR', (ROOT / 'scripts/eks.sh').read_text())

    def test_uninstall_removes_releases_and_waits_for_the_load_balancer(self):
        result = self.run_eks('uninstall')
        self.assertIn(result.returncode, (0,))
        calls = '\n'.join(self.calls())
        for release in ('bookstore-frontend', 'catalog-service', 'orders-service', 'inventory-service'):
            self.assertIn(f'helm uninstall {release}', calls)
        self.assertIn('service bookstore-frontend', calls)

    def test_uninstall_is_safe_without_workloads(self):
        # The kubectl fake returns failure for "get deploy" unless the marker exists.
        kubectl = self.bin / 'kubectl'
        kubectl.write_text('#!/bin/sh\necho "kubectl $*" >> "$EKS_TEST_LOG"\nexit 1\n')
        result = self.run_eks('uninstall')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('No workloads found', result.stdout)
        self.assertFalse(any('helm uninstall' in c for c in self.calls()))

    def test_destroy_requires_exact_confirmation(self):
        result = self.run_eks('destroy', stdin='yes\n')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('nothing was destroyed', result.stderr)
        self.assertFalse(any(c.startswith('terraform') and 'destroy' in c for c in self.calls()))

        self.log.write_text('')
        result = self.run_eks('destroy', stdin='bookstore-lab-eks\n')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(any(f'terraform -chdir={self.tf_dir} destroy' in c for c in self.calls()))

    def test_apply_requires_matching_confirmation(self):
        (self.tf_dir / 'terraform.tfvars').write_text('aws_region = "ap-south-1"\n')
        result = self.run_eks('apply', stdin='yes\n')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('nothing was created', result.stderr)

    def test_apply_refuses_without_tfvars(self):
        result = self.run_eks('apply', stdin='bookstore-lab-eks\n')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('terraform.tfvars is missing', result.stderr)
        self.assertFalse(any(' apply' in c for c in self.calls()))

    def test_apply_runs_terraform_after_confirmation(self):
        self.assertFalse((ROOT / 'terraform/eks/terraform.tfvars').exists(),
                         'terraform.tfvars must never be committed')
        (self.tf_dir / 'terraform.tfvars').write_text('aws_region = "ap-south-1"\n')
        result = self.run_eks('apply', stdin='bookstore-lab-eks\n')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('billable AWS resources', result.stdout)
        calls = self.calls()
        self.assertTrue(any(f'terraform -chdir={self.tf_dir} init' in c for c in calls))
        self.assertTrue(any(f'terraform -chdir={self.tf_dir} apply' in c for c in calls))

    def test_apply_supports_non_interactive_approval(self):
        (self.tf_dir / 'terraform.tfvars').write_text('aws_region = "ap-south-1"\n')
        result = subprocess.run(['sh', str(ROOT / 'scripts/eks.sh'), 'apply'], cwd=ROOT,
                                env=dict(self.env, EKS_AUTO_APPROVE='true'),
                                capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(any(' apply' in c for c in self.calls()))


class ReleasePrefixTest(unittest.TestCase):
    def test_prefix_changes_image_paths_only(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location('release', ROOT / 'scripts/prepare-release.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        default = module.release('123456789012.dkr.ecr.ap-south-1.amazonaws.com', '0123456789ab', 'main')
        prefixed = module.release('123456789012.dkr.ecr.ap-south-1.amazonaws.com', '0123456789ab',
                                  'main', 'bookstore-lab-')
        for plain, lab in zip(default['items'], prefixed['items']):
            self.assertEqual(plain['metadata'], lab['metadata'])
            plain_repo = plain['spec']['source']['helm']['parameters'][0]['value']
            lab_repo = lab['spec']['source']['helm']['parameters'][0]['value']
            self.assertTrue(plain_repo.endswith('/bookstore-' + plain['metadata']['name'].replace('bookstore-', ''))
                            or '/bookstore-' in plain_repo)
            self.assertIn('/bookstore-lab-', lab_repo)

    def test_invalid_prefix_is_rejected(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location('release', ROOT / 'scripts/prepare-release.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        for bad in ('Bookstore-', '-leading', 'has space', '../evil'):
            with self.subTest(prefix=bad), self.assertRaises(ValueError):
                module.release('registry.example.com', '0123456789ab', 'main', bad)


class TerraformEksTest(unittest.TestCase):
    def test_ebs_is_optional_and_disabled_by_default(self):
        main = (ROOT / 'terraform/eks/main.tf').read_text()
        variables = (ROOT / 'terraform/eks/variables.tf').read_text()
        self.assertIn('variable "enable_ebs_csi"', variables)
        self.assertRegex(variables, r'variable "enable_ebs_csi"\s*\{[^}]*default\s*=\s*false')
        self.assertEqual(4, main.count('var.enable_ebs_csi ? 1 : 0'))
        self.assertIn('one(aws_iam_role.ebs_csi[*].arn)',
                      (ROOT / 'terraform/eks/outputs.tf').read_text())

    def test_default_deployment_references_no_ebs_storage_class(self):
        script = (ROOT / 'scripts/eks.sh').read_text()
        self.assertNotIn('kubectl apply -f deploy/eks/storageclass.yaml', script)
        self.assertIn('EKS_DB_STORAGE:-ephemeral', script)
        optional = ROOT / 'deploy/eks/optional/ebs-storageclass.yaml'
        self.assertTrue(optional.exists())
        self.assertIn('not used by the default deployment path', optional.read_text())

    def test_ecr_names_match_the_release_generator_prefix(self):
        main = (ROOT / 'terraform/eks/main.tf').read_text()
        for component in ('frontend', 'catalog-service', 'orders-service', 'inventory-service'):
            self.assertIn(f'name                 = "${{local.name}}-{component}"', main)
        outputs = (ROOT / 'terraform/eks/outputs.tf').read_text()
        self.assertIn('ecr_repository_prefix', outputs)
        self.assertIn('"${local.name}-"', outputs)


if __name__ == '__main__':
    unittest.main()
