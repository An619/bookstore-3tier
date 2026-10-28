import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('release', Path(__file__).resolve().parents[1] / 'scripts/prepare-release.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ReleaseTest(unittest.TestCase):
    def test_four_independent_releases(self):
        apps = module.release('docker.io/demo', 'a' * 12, 'arena/test')['items']
        self.assertEqual(4, len(apps))
        self.assertEqual(4, len({a['metadata']['name'] for a in apps}))
        for app in apps:
            source = app['spec']['source']
            self.assertTrue(Path(source['path'], 'Chart.yaml').is_file())
            self.assertEqual('arena/test', source['targetRevision'])
            self.assertEqual('a' * 12, source['helm']['parameters'][1]['value'])
            self.assertNotIn('automated', app['spec']['syncPolicy'])
        self.assertEqual(['bookstore-frontend', 'catalog-service', 'orders-service', 'inventory-service'],
                         [a['spec']['source']['helm']['releaseName'] for a in apps])

    def test_reject_mutable_or_invalid_input(self):
        for registry, sha, branch in [('docker.io/demo', 'latest', 'main'),
                                      ('docker.io/demo/', 'a' * 12, 'main'),
                                      ('docker.io/demo', 'a' * 12, '')]:
            with self.assertRaises(ValueError):
                module.release(registry, sha, branch)


if __name__ == '__main__':
    unittest.main()
