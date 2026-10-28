"""Drive the smoke test against an in-memory fake of the async checkout API."""
import importlib.util
import re
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('smoke', Path(__file__).resolve().parents[1] / 'scripts/smoke-test.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

CHECKOUT = re.compile(r'^/api/checkout$')
ORDER = re.compile(r'^/api/orders/(\d+)$')


class FakeApi:
    """Stateful imitation of the gateway: checkout queues, worker confirms."""

    def __init__(self, stock=5, checkout_status=202, delete_status=204, confirm_after=1):
        self.stock = stock
        self.checkout_status = checkout_status
        self.delete_status = delete_status
        self.confirm_after = confirm_after
        self.orders = {}
        self.attempts = {}
        self.pending = {}
        self.next_id = 1
        self.polls = {}
        self.calls = []

    def __call__(self, base, path, method='GET', data=None, expected=200, headers=None):
        status, body = self.route(method, path, data, headers)
        self.calls.append((method, path))
        if status != expected:
            raise RuntimeError(f'{method} {path}: expected {expected}, got {status}')
        return body

    def confirm(self, order_id):
        """Simulate the outbox worker reserving stock successfully."""
        order = self.orders[order_id]
        if self.stock < order['quantity']:
            order['status'] = 'CANCELLED'
        else:
            self.stock -= order['quantity']
            order['status'] = 'CONFIRMED'

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
            if self.checkout_status != 202:
                return self.checkout_status, None
            if key in self.attempts:
                return 202, self.orders[self.attempts[key]]
            order = {'id': self.next_id, 'bookId': data['bookId'], 'quantity': data['quantity'],
                     'status': 'PENDING'}
            self.next_id += 1
            self.orders[order['id']] = order
            self.attempts[key] = order['id']
            self.polls[order['id']] = 0
            return 202, order
        match = ORDER.match(path)
        if match:
            order_id = int(match.group(1))
            if method == 'GET':
                if order_id not in self.orders:
                    return 404, None
                order = self.orders[order_id]
                if order['status'] == 'PENDING':
                    self.polls[order_id] += 1
                    if self.polls[order_id] >= self.confirm_after:
                        self.confirm(order_id)
                return 200, order
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
    def setUp(self):
        # Virtualise the clock so polling loops are instant but deadlines still bite.
        self.now = 0.0

        def advance(seconds):
            self.now += seconds

        for target, value in (('sleep', advance), ('monotonic', lambda: self.now)):
            patcher = patch.object(module.time, target, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        printer = patch('builtins.print')
        printer.start()
        self.addCleanup(printer.stop)

    def run_smoke(self, api):
        with patch.object(module, 'request', side_effect=api):
            module.smoke('http://test')

    def test_happy_path_polls_until_confirmed_and_cleans_up(self):
        api = FakeApi(confirm_after=3)
        self.run_smoke(api)
        self.assertEqual(5, api.stock)
        self.assertEqual({}, api.orders)
        self.assertEqual(1, api.methods().count('DELETE'))
        self.assertGreaterEqual(api.polls[1], 3, 'the smoke test must poll for confirmation')
        # Checkout was submitted twice (submit + replay), both with 202.
        self.assertEqual(2, len([p for m, p in api.calls if p == '/api/checkout']))

    def test_cancelled_checkout_fails_clearly_and_restores_nothing(self):
        api = FakeApi(stock=0)
        with self.assertRaisesRegex(RuntimeError, 'No stocked catalog book'):
            self.run_smoke(api)

    def test_rejection_never_deletes_an_order(self):
        api = FakeApi(checkout_status=400)
        with self.assertRaisesRegex(RuntimeError, 'expected 202, got 400'):
            self.run_smoke(api)
        self.assertNotIn('DELETE', api.methods())

    def test_cleanup_failure_is_reported(self):
        api = FakeApi(delete_status=500)
        with self.assertRaisesRegex(RuntimeError, 'could not delete diagnostic order 1'):
            self.run_smoke(api)
        self.assertEqual(5, api.stock, 'stock must still be released')

    def test_worker_never_confirming_times_out(self):
        api = FakeApi(confirm_after=10 ** 6)
        with self.assertRaisesRegex(RuntimeError, 'did not reach CONFIRMED'):
            self.run_smoke(api)
        self.assertIn('DELETE', api.methods(), 'cleanup must still run after a timeout')
        self.assertEqual(5, api.stock)

    def test_cancelled_order_is_reported(self):
        api = FakeApi()
        api.confirm = lambda order_id: api.orders[order_id].update(status='CANCELLED')
        with self.assertRaisesRegex(RuntimeError, 'cancelled by the worker'):
            self.run_smoke(api)

    def test_empty_catalog_fails(self):
        with patch.object(module, 'request', side_effect=[[], [], []]):
            with self.assertRaisesRegex(RuntimeError, 'Catalog is empty'):
                module.smoke('http://test')


if __name__ == '__main__':
    unittest.main()
