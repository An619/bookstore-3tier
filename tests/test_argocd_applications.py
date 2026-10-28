"""Guard the Helm release names behind the Service DNS used by frontend Nginx."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {
    'argocd/catalog-service-application.yaml': ('helm/catalog-service', 'catalog-service'),
    'argocd/orders-service-application.yaml': ('helm/orders-service', 'orders-service'),
    'argocd/inventory-service-application.yaml': ('helm/inventory-service', 'inventory-service'),
    'argocd/frontend-application.yaml': ('helm/frontend', 'bookstore-frontend'),
}
RELEASE = re.compile(r'^\s+releaseName:\s*(\S+)\s*$', re.MULTILINE)


class ArgoApplicationTest(unittest.TestCase):
    def test_static_applications_pin_release_names(self):
        for relative, (chart_path, release) in EXPECTED.items():
            text = (ROOT / relative).read_text()
            self.assertIn(f'path: {chart_path}', text, relative)
            self.assertEqual([release], RELEASE.findall(text), relative)

    def test_local_lab_applications_pin_the_same_release_names(self):
        text = (ROOT / 'deploy/local/argocd/applications.yaml').read_text()
        self.assertEqual(sorted(release for _, release in EXPECTED.values()),
                         sorted(RELEASE.findall(text)))


if __name__ == '__main__':
    unittest.main()
