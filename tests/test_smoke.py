import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('smoke', Path(__file__).resolve().parents[1] / 'scripts/smoke-test.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SmokeTest(unittest.TestCase):
    def replies(self):
        return [[{'id': 1}], [{'bookId': 1, 'availableQuantity': 5}], [],
                None, None, {'availableQuantity': 4}, {'id': 7},
                {'status': 'CREATED', 'bookId': 1}, {'availableQuantity': 5},
                {'availableQuantity': 5}, None]

    def test_success_checks_routing_and_compensation(self):
        with patch.object(module, 'request', side_effect=self.replies()) as request:
            module.smoke('http://test')
            self.assertEqual(11, request.call_count)
            self.assertIn('/release?quantity=1', request.call_args_list[8].args[1])

    def test_failed_order_still_releases_stock(self):
        replies = self.replies()[:6] + [RuntimeError('order failure'), {}]
        with patch.object(module, 'request', side_effect=replies) as request:
            with self.assertRaisesRegex(RuntimeError, 'order failure'):
                module.smoke('http://test')
            self.assertIn('/release?quantity=1', request.call_args.args[1])

    def test_empty_catalog_fails(self):
        with patch.object(module, 'request', side_effect=[[], [], []]):
            with self.assertRaisesRegex(RuntimeError, 'Catalog is empty'):
                module.smoke('http://test')


if __name__ == '__main__':
    unittest.main()
