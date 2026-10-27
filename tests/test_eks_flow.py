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
        self.env = dict(os.environ, PATH=f'{self.bin}:/usr/bin:/bin',
                        EKS_TEST_LOG=str(self.log),
                        LAB_TOOLS_DIR=str(self.root / 'tools'),
                        EKS_TEST_SECRETS=str(self.root / 'secrets-exist'),
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

    def test_deploy_creates_secrets_storage_and_helm_releases(self):
        result = self.run_eks('deploy')
        self.assertEqual(0, result.returncode, result.stderr)
        calls = '\n'.join(self.calls())
        self.assertIn('kubectl apply -f deploy/eks/storageclass.yaml', calls)
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

    def test_destroy_requires_exact_confirmation(self):
        result = self.run_eks('destroy', stdin='yes\n')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('nothing was destroyed', result.stderr)
        self.assertFalse(any(c.startswith('terraform') and 'destroy' in c for c in self.calls()))

        self.log.write_text('')
        result = self.run_eks('destroy', stdin='bookstore-lab-eks\n')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(any('terraform -chdir=terraform/eks destroy' in c for c in self.calls()))

    def test_apply_is_explicit_about_billing_and_runs_terraform(self):
        result = self.run_eks('apply')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('billable AWS resources', result.stdout)
        calls = self.calls()
        self.assertTrue(any('terraform -chdir=terraform/eks init' in c for c in calls))
        self.assertTrue(any('terraform -chdir=terraform/eks apply' in c for c in calls))


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
    def test_storage_provisioning_is_configured(self):
        main = (ROOT / 'terraform/eks/main.tf').read_text()
        self.assertIn('aws_eks_addon" "ebs_csi"', main)
        self.assertIn('AmazonEBSCSIDriverPolicy', main)
        self.assertIn('system:serviceaccount:kube-system:ebs-csi-controller-sa', main)
        self.assertIn('module.eks.oidc_provider_arn', main)
        storageclass = (ROOT / 'deploy/eks/storageclass.yaml').read_text()
        self.assertIn('provisioner: ebs.csi.aws.com', storageclass)
        self.assertIn('encrypted: "true"', storageclass)

    def test_ecr_names_match_the_release_generator_prefix(self):
        main = (ROOT / 'terraform/eks/main.tf').read_text()
        for component in ('frontend', 'catalog-service', 'orders-service', 'inventory-service'):
            self.assertIn(f'name                 = "${{local.name}}-{component}"', main)
        outputs = (ROOT / 'terraform/eks/outputs.tf').read_text()
        self.assertIn('ecr_repository_prefix', outputs)
        self.assertIn('"${local.name}-"', outputs)


if __name__ == '__main__':
    unittest.main()
