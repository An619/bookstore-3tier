import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('smoke', Path(__file__).resolve().parents[1] / 'scripts/smoke-test.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SmokeTest(unittest.TestCase):
    def replies(self):
        # Order: readiness, validation, reserve, read-back, release, delete,
        # stock check, unknown route, deleted-order read-back.
        return [[{'id': 1}], [{'bookId': 1, 'availableQuantity': 5}], [],
                None, None, {'availableQuantity': 4}, {'id': 7},
                {'status': 'CREATED', 'bookId': 1}, None, None,
                {'availableQuantity': 5}, None, None]

    def test_success_cleans_up_order_and_stock(self):
        with patch.object(module, 'request', side_effect=self.replies()) as request:
            module.smoke('http://test')
            calls = [(c.args[1], c.kwargs.get('method')) for c in request.call_args_list]
            self.assertEqual(13, len(calls))
            self.assertIn(('/api/inventory/1/release?quantity=1', 'POST'), calls)
            self.assertIn(('/api/orders/7', 'DELETE'), calls)
            self.assertEqual(('/api/orders/7', None), calls[-1])

    def test_failure_after_order_creation_still_compensates(self):
        replies = self.replies()[:7] + [RuntimeError('read-back failure'), None, None]
        with patch.object(module, 'request', side_effect=replies) as request:
            with self.assertRaisesRegex(RuntimeError, 'read-back failure'):
                module.smoke('http://test')
            calls = [(c.args[1], c.kwargs.get('method')) for c in request.call_args_list]
            self.assertIn(('/api/inventory/1/release?quantity=1', 'POST'), calls)
            self.assertIn(('/api/orders/7', 'DELETE'), calls)

    def test_failure_before_order_creation_does_not_delete(self):
        replies = self.replies()[:5] + [RuntimeError('reserve failure')]
        with patch.object(module, 'request', side_effect=replies) as request:
            with self.assertRaisesRegex(RuntimeError, 'reserve failure'):
                module.smoke('http://test')
            methods = [c.kwargs.get('method') for c in request.call_args_list]
            self.assertNotIn('DELETE', methods)

    def test_cleanup_failure_is_reported(self):
        replies = self.replies()[:9] + [RuntimeError('delete failed')]
        with patch.object(module, 'request', side_effect=replies):
            with self.assertRaisesRegex(RuntimeError, 'could not delete diagnostic order 7'):
                module.smoke('http://test')

    def test_original_failure_is_not_masked_by_cleanup(self):
        replies = self.replies()[:7] + [RuntimeError('order failure'),
                                        RuntimeError('release failed'), None]
        with patch.object(module, 'request', side_effect=replies):
            with self.assertRaisesRegex(RuntimeError, 'order failure; cleanup also failed'):
                module.smoke('http://test')

    def test_empty_catalog_fails(self):
        with patch.object(module, 'request', side_effect=[[], [], []]):
            with self.assertRaisesRegex(RuntimeError, 'Catalog is empty'):
                module.smoke('http://test')


if __name__ == '__main__':
    unittest.main()
