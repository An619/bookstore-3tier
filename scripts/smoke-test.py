#!/usr/bin/env python3
"""Exercise a disposable stack end-to-end through the server-side checkout API.

Creates one demo order, verifies idempotent replay, then deletes the order and
releases the reserved stock. Never run against production data.
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

    key = f'smoke-{uuid.uuid4().hex}'
    order_id = None
    reserved = False
    failure = None
    try:
        order = request(base, '/api/checkout', method='POST', expected=201,
                        headers={'Idempotency-Key': key},
                        data={'customerName': 'CI Smoke Test', 'bookId': book_id, 'quantity': 1})
        order_id = order['id']
        reserved = True
        after = request(base, path)
        if after['availableQuantity'] != book['availableQuantity'] - 1:
            raise RuntimeError('Checkout did not decrement stock exactly once')
        replay = request(base, '/api/checkout', method='POST', expected=201,
                         headers={'Idempotency-Key': key},
                         data={'customerName': 'CI Smoke Test', 'bookId': book_id, 'quantity': 1})
        if replay['id'] != order_id:
            raise RuntimeError('Idempotent replay returned a different order')
        if request(base, path)['availableQuantity'] != after['availableQuantity']:
            raise RuntimeError('Idempotent replay reserved stock twice')
        loaded = request(base, f'/api/orders/{order_id}')
        if loaded['status'] != 'CREATED' or loaded['bookId'] != book_id:
            raise RuntimeError('Created order cannot be read back correctly')
    except (URLError, RuntimeError, ValueError, KeyError) as error:
        failure = error

    # Always compensate, and always report the original failure rather than the
    # cleanup result.
    cleanup_failures = []
    if reserved:
        try:
            request(base, path + '/release?quantity=1', method='POST')
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
    print('PASS: readiness, inventory validation, idempotent checkout, replay, cleanup and routing')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8080')
    parser.add_argument('--timeout', type=int, default=180)
    args = parser.parse_args()
    smoke(args.base_url.rstrip('/'), args.timeout)
