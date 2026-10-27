#!/usr/bin/env python3
"""Exercise a disposable stack. Creates one demo order; never run against production."""
import argparse
import json
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def request(base, path, method='GET', data=None, expected=200):
    body = None if data is None else json.dumps(data).encode()
    req = Request(base + path, data=body, method=method,
                  headers={'Content-Type': 'application/json'})
    try:
        response = urlopen(req, timeout=10)
    except HTTPError as error:
        response = error
    with response:
        payload = response.read()
        if response.status != expected:
            raise RuntimeError(f'{method} {path}: expected {expected}, got {response.status}')
        return json.loads(payload) if payload else None


def smoke(base, timeout=180):
    deadline = time.monotonic() + timeout
    while True:
        try:
            books = request(base, '/api/catalog/books')
            stocks = request(base, '/api/inventory')
            request(base, '/api/orders')
            break
        except (URLError, RuntimeError, ValueError):
            if time.monotonic() >= deadline:
                raise RuntimeError('Stack did not become ready before the deadline')
            time.sleep(3)
    if not books:
        raise RuntimeError('Catalog is empty')
    book = next((s for s in stocks if s['availableQuantity'] > 0
                 and any(b['id'] == s['bookId'] for b in books)), None)
    if book is None:
        raise RuntimeError('No stocked catalog book available for smoke test')
    path = f"/api/inventory/{book['bookId']}"
    request(base, path + '/reserve?quantity=0', method='POST', expected=400)
    request(base, path + '/reserve?quantity=2147483647', method='POST', expected=409)
    reserved = False
    order_id = None
    failure = None
    try:
        stock = request(base, path + '/reserve?quantity=1', method='POST')
        reserved = True
        if stock['availableQuantity'] != book['availableQuantity'] - 1:
            raise RuntimeError('Reservation did not decrement stock')
        order = request(base, '/api/orders', method='POST', expected=201, data={
            'customerName': 'CI Smoke Test', 'bookId': book['bookId'], 'quantity': 1,
        })
        order_id = order['id']
        loaded = request(base, f'/api/orders/{order_id}')
        if loaded['status'] != 'CREATED' or loaded['bookId'] != book['bookId']:
            raise RuntimeError('Created order cannot be read back correctly')
    except (URLError, RuntimeError, ValueError) as error:
        failure = error
    # Always compensate, even when an assertion above failed, and always report
    # the original failure rather than the cleanup result.
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
    request(base, '/api/not-a-route', expected=404)
    request(base, f'/api/orders/{order_id}', expected=404)
    print('PASS: catalog, inventory validation/reserve/release, order create/read/delete and routing')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8080')
    parser.add_argument('--timeout', type=int, default=180)
    args = parser.parse_args()
    smoke(args.base_url.rstrip('/'), args.timeout)
