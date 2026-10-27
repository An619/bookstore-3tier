"""Drive the smoke test against an in-memory fake of the API."""
import importlib.util
from pathlib import Path
import re
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('smoke', Path(__file__).resolve().parents[1] / 'scripts/smoke-test.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

CHECKOUT = re.compile(r'^/api/checkout$')


class FakeApi:
    """Small stateful imitation of the gateway paths used by the smoke test."""

    def __init__(self, stock=5, checkout_status=201, delete_status=204):
        self.stock = stock
        self.checkout_status = checkout_status
        self.delete_status = delete_status
        self.orders = {}
        self.attempts = {}
        self.next_id = 1
        self.calls = []

    def __call__(self, base, path, method='GET', data=None, expected=200, headers=None):
        status, body = self.route(method, path, data, headers)
        self.calls.append((method, path))
        if status != expected:
            raise RuntimeError(f'{method} {path}: expected {expected}, got {status}')
        return body

    def route(self, method, path, data, headers):
        if method == 'GET' and path == '/api/catalog/books':
            return 200, [{'id': 1}]
        if method == 'GET' and path == '/api/inventory':
            return 200, [{'bookId': 1, 'availableQuantity': self.stock}]
        if method == 'GET' and path == '/api/orders':
            return 200, list(self.orders.values())
        if method == 'GET' and path == '/api/inventory/1':
            return 200, {'availableQuantity': self.stock}
        if method == 'POST' and path.startswith('/api/inventory/1/reserve'):
            quantity = int(path.split('quantity=')[1])
            if quantity < 1:
                return 400, None
            if quantity > self.stock:
                return 409, None
            self.stock -= quantity
            return 200, {'availableQuantity': self.stock}
        if method == 'POST' and path.startswith('/api/inventory/1/release'):
            self.stock += int(path.split('quantity=')[1])
            return 200, {'availableQuantity': self.stock}
        if method == 'POST' and CHECKOUT.match(path):
            key = (headers or {}).get('Idempotency-Key')
            if not key:
                return 400, None
            if self.checkout_status != 201:
                return self.checkout_status, None
            if key in self.attempts:
                return 201, self.orders[self.attempts[key]]
            if self.stock < data['quantity']:
                return 409, None
            self.stock -= data['quantity']
            order = {'id': self.next_id, 'bookId': data['bookId'], 'status': 'CREATED'}
            self.next_id += 1
            self.orders[order['id']] = order
            self.attempts[key] = order['id']
            return 201, order
        match = re.match(r'^/api/orders/(\d+)$', path)
        if match:
            order_id = int(match.group(1))
            if method == 'GET':
                return (200, self.orders[order_id]) if order_id in self.orders else (404, None)
            if method == 'DELETE':
                if self.delete_status != 204:
                    return self.delete_status, None
                if order_id not in self.orders:
                    return 404, None
                del self.orders[order_id]
                return 204, None
        if path == '/api/not-a-route':
            return 404, None
        raise AssertionError(f'Unexpected call: {method} {path}')

    def methods(self):
        return [method for method, _ in self.calls]


class SmokeTest(unittest.TestCase):
    def run_smoke(self, api):
        with patch.object(module, 'request', side_effect=api):
            module.smoke('http://test')

    def test_happy_path_replays_and_cleans_up(self):
        api = FakeApi()
        self.run_smoke(api)
        self.assertEqual(1, api.methods().count('DELETE'))
        self.assertEqual(5, api.stock)
        self.assertEqual({}, api.orders)
        checkouts = [p for m, p in api.calls if p == '/api/checkout']
        self.assertEqual(2, len(checkouts))

    def test_checkout_failure_reports_and_deletes_nothing(self):
        api = FakeApi(checkout_status=502)
        with self.assertRaisesRegex(RuntimeError, 'expected 201, got 502'):
            self.run_smoke(api)
        self.assertNotIn('DELETE', api.methods())
        self.assertEqual(5, api.stock)

    def test_cleanup_failure_is_reported(self):
        api = FakeApi(delete_status=500)
        with self.assertRaisesRegex(RuntimeError, 'could not delete diagnostic order 1'):
            self.run_smoke(api)
        self.assertEqual(5, api.stock)

    def test_readback_failure_still_compensates(self):
        api = FakeApi()
        original = api.route

        def broken_read(method, path, data, headers):
            if method == 'GET' and path == '/api/orders/1':
                return 500, None
            return original(method, path, data, headers)

        api.route = broken_read
        with self.assertRaisesRegex(RuntimeError, 'expected 200, got 500'):
            self.run_smoke(api)
        self.assertIn('DELETE', api.methods())
        self.assertEqual(5, api.stock)

    def test_empty_catalog_fails(self):
        with patch.object(module, 'request', side_effect=[[], [], []]):
            with self.assertRaisesRegex(RuntimeError, 'Catalog is empty'):
                module.smoke('http://test')

    def test_no_stocked_book_fails(self):
        api = FakeApi(stock=0)
        with self.assertRaisesRegex(RuntimeError, 'No stocked catalog book'):
            self.run_smoke(api)


if __name__ == '__main__':
    unittest.main()
