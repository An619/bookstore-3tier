#!/usr/bin/env python3
"""Exercise a disposable stack end-to-end through the server-side checkout API.

Checkout is asynchronous: the order is accepted with 202 and confirmed by the
orders outbox worker once inventory reserves the stock. This script submits an
order, waits for CONFIRMED, verifies idempotent replay, then releases the stock
and deletes the order. Never run against production data.
"""
import argparse
import json
import time
import uuid
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def request(base, path, method='GET', data=None, expected=200, headers=None):
    body = None if data is None else json.dumps(data).encode()
    request_headers = {'Content-Type': 'application/json'}
    request_headers.update(headers or {})
    req = Request(base + path, data=body, method=method, headers=request_headers)
    try:
        response = urlopen(req, timeout=10)
    except HTTPError as error:
        response = error
    with response:
        payload = response.read()
        if response.status != expected:
            detail = payload.decode(errors='replace')[:200]
            raise RuntimeError(f'{method} {path}: expected {expected}, got {response.status} {detail}')
        return json.loads(payload) if payload else None


def wait_until_ready(base, timeout):
    deadline = time.monotonic() + timeout
    while True:
        try:
            books = request(base, '/api/catalog/books')
            stocks = request(base, '/api/inventory')
            request(base, '/api/orders')
            return books, stocks
        except (URLError, RuntimeError, ValueError):
            if time.monotonic() >= deadline:
                raise RuntimeError('Stack did not become ready before the deadline')
            time.sleep(3)


def wait_for_order(base, order_id, expected, timeout):
    """Poll until the outbox worker has finalised the order."""
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        order = request(base, f'/api/orders/{order_id}')
        last = order.get('status')
        if last == expected:
            return order
        if last in ('CANCELLED',):
            raise RuntimeError(f'Checkout was cancelled by the worker (status {last})')
        time.sleep(2)
    raise RuntimeError(f'Order {order_id} did not reach {expected} (last status: {last})')


def smoke(base, timeout=180):
    books, stocks = wait_until_ready(base, timeout)
    if not books:
        raise RuntimeError('Catalog is empty')
    book = next((s for s in stocks if s['availableQuantity'] > 0
                 and any(b['id'] == s['bookId'] for b in books)), None)
    if book is None:
        raise RuntimeError('No stocked catalog book available for smoke test')
    book_id = book['bookId']
    path = f'/api/inventory/{book_id}'

    # Direct API validation still matters for the inventory contract.
    request(base, path + '/reserve?quantity=0', method='POST', expected=400)
    request(base, path + '/reserve?quantity=2147483647', method='POST', expected=409)

    key = f'smoke-{uuid.uuid4().hex[:24]}'
    order_id = None
    reserved = False
    failure = None
    try:
        accepted = request(base, '/api/checkout', method='POST', expected=202,
                           headers={'Idempotency-Key': key},
                           data={'customerName': 'CI Smoke Test', 'bookId': book_id, 'quantity': 1})
        order_id = accepted['id']
        if accepted['status'] != 'PENDING':
            raise RuntimeError(f'Checkout should be queued as PENDING, got {accepted["status"]}')

        confirmed = wait_for_order(base, order_id, 'CONFIRMED', timeout=90)
        reserved = True
        if confirmed['bookId'] != book_id:
            raise RuntimeError('Confirmed order has the wrong book')

        after = request(base, path)
        if after['availableQuantity'] != book['availableQuantity'] - 1:
            raise RuntimeError('Checkout did not decrement stock exactly once')

        replay = request(base, '/api/checkout', method='POST', expected=202,
                         headers={'Idempotency-Key': key},
                         data={'customerName': 'CI Smoke Test', 'bookId': book_id, 'quantity': 1})
        if replay['id'] != order_id:
            raise RuntimeError('Idempotent replay returned a different order')
        if request(base, path)['availableQuantity'] != after['availableQuantity']:
            raise RuntimeError('Idempotent replay reserved stock twice')
    except (URLError, RuntimeError, ValueError, KeyError) as error:
        failure = error

    # Always compensate, and report the original failure rather than the cleanup.
    cleanup_failures = []
    if reserved:
        try:
            request(base, path + '/release?quantity=1', method='POST',
                    headers={'Idempotency-Key': f'{key}:cleanup'})
        except (URLError, RuntimeError, ValueError) as error:
            cleanup_failures.append(f'stock release failed: {error}')
    if order_id is not None:
        try:
            request(base, f'/api/orders/{order_id}', method='DELETE', expected=204)
        except (URLError, RuntimeError, ValueError) as error:
            cleanup_failures.append(f'could not delete diagnostic order {order_id}: {error}')
    if failure is not None:
        if cleanup_failures:
            raise RuntimeError(f'{failure}; cleanup also failed: {"; ".join(cleanup_failures)}')
        raise failure
    if cleanup_failures:
        raise RuntimeError('; '.join(cleanup_failures))

    if request(base, path)['availableQuantity'] != book['availableQuantity']:
        raise RuntimeError('Stock compensation did not restore the initial quantity')
    request(base, f'/api/orders/{order_id}', expected=404)
    request(base, '/api/not-a-route', expected=404)
    print('PASS: readiness, inventory validation, async checkout, confirmation, '
          'idempotent replay, cleanup and routing')


def readiness_only(base, timeout):
    """Wait until the stack serves the API, without writing any data."""
    books, stocks = wait_until_ready(base, timeout)
    print(f'PASS: stack is serving {len(books)} books and {len(stocks)} inventory rows')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8080')
    parser.add_argument('--timeout', type=int, default=180)
    parser.add_argument('--check-only', action='store_true',
                        help='only wait for readiness; do not create an order')
    args = parser.parse_args()
    base = args.base_url.rstrip('/')
    if args.check_only:
        readiness_only(base, args.timeout)
    else:
        smoke(base, args.timeout)
