// Browse-heavy load test with a checkout tail, used to back the SLOs with data.
//
//   k6 run perf/checkout-load.js                          # 25 virtual users, 2m
//   k6 run -e BASE_URL=http://<lb> -e VUS=50 -e DURATION=5m perf/checkout-load.js
//
// What it proves:
//   * reads stay inside the 1.5s p95 latency objective under load;
//   * errors stay inside the 0.5% availability budget (99.5% success);
//   * the asynchronous checkout saga confirms at least 95% of orders under load
//     (the orchestration SLO), rather than merely accepting 202s.
//
// It writes real orders, then deletes them, and releases the reserved stock, so
// only point it at a disposable environment.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { randomIntBetween } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = parseInt(__ENV.VUS || '25', 10);
const DURATION = __ENV.DURATION || '2m';
const CHECKOUT_RATIO = parseFloat(__ENV.CHECKOUT_RATIO || '0.15');
const ORDER_POLL_TIMEOUT_S = parseInt(__ENV.ORDER_POLL_TIMEOUT_S || '30', 10);

const confirms = new Rate('checkout_confirmed');
const checkoutLatency = new Trend('checkout_accept_latency', true);

export const options = {
  scenarios: {
    browsers: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '20s', target: VUS },
        { duration: DURATION, target: VUS },
        { duration: '20s', target: 0 },
      ],
      gracefulStop: '30s',
    },
  },
  thresholds: {
    // Availability objective: <0.5% failed requests (99.5% SLO).
    http_req_failed: ['rate<0.005'],
    // Latency objective: p95 under 1.5s for everything the browser calls.
    http_req_duration: ['p(95)<1500', 'p(99)<3000'],
    // Checkout acceptance should be fast; the work happens asynchronously.
    checkout_accept_latency: ['p(95)<800'],
    // Orchestration objective: at least 95% of checkouts confirm.
    checkout_confirmed: ['rate>=0.95'],
  },
};

function jsonHeaders(extra = {}) {
  return Object.assign({ 'Content-Type': 'application/json' }, extra);
}

export function setup() {
  const books = http.get(`${BASE}/api/catalog/books`);
  check(books, { 'catalog is reachable': (r) => r.status === 200 });
  const stocks = http.get(`${BASE}/api/inventory`);
  check(stocks, { 'inventory is reachable': (r) => r.status === 200 });
  const available = (stocks.json() || []).filter((row) => row.availableQuantity > 0);
  if (available.length === 0) {
    throw new Error('No stocked book: seed the catalog before load testing');
  }
  return { bookIds: available.map((row) => row.bookId) };
}

function pollOrder(orderId) {
  const deadline = Date.now() + ORDER_POLL_TIMEOUT_S * 1000;
  while (Date.now() < deadline) {
    const response = http.get(`${BASE}/api/orders/${orderId}`);
    if (response.status === 200) {
      const status = response.json('status');
      if (status === 'CONFIRMED' || status === 'CANCELLED') {
        return status;
      }
    }
    sleep(0.5);
  }
  return 'TIMEOUT';
}

export default function (data) {
  const bookIds = data.bookIds;

  // Browsing, the dominant traffic.
  http.get(`${BASE}/api/catalog/books`);
  const bookId = bookIds[randomIntBetween(0, bookIds.length - 1)];
  http.get(`${BASE}/api/inventory/${bookId}`);
  http.get(`${BASE}/api/orders`);

  if (Math.random() >= CHECKOUT_RATIO) {
    sleep(randomIntBetween(1, 3) / 10);
    return;
  }

  // The checkout tail: accept, then wait for the saga to settle.
  const key = `load-${__VU}-${__ITER}-${Date.now()}`;
  const started = Date.now();
  const checkout = http.post(
    `${BASE}/api/checkout`,
    JSON.stringify({ customerName: `Load test VU${__VU}`, bookId, quantity: 1 }),
    { headers: jsonHeaders({ 'Idempotency-Key': key }), tags: { name: 'checkout' } },
  );
  checkoutLatency.add(Date.now() - started);

  const accepted = check(checkout, {
    'checkout accepted (202)': (r) => r.status === 202,
    'checkout queued as PENDING': (r) => r.json('status') === 'PENDING',
  });
  if (!accepted) {
    return;
  }

  const orderId = checkout.json('id');
  const status = pollOrder(orderId);
  confirms.add(status === 'CONFIRMED');
  check(status, {
    'checkout confirmed by the worker': (s) => s === 'CONFIRMED',
  });

  // Clean up only what this iteration created.
  if (status === 'CONFIRMED') {
    http.post(`${BASE}/api/inventory/${bookId}/release?quantity=1`, null, {
      headers: jsonHeaders({ 'Idempotency-Key': `${key}:cleanup` }),
      tags: { name: 'release' },
    });
  }
  http.del(`${BASE}/api/orders/${orderId}`, null, { tags: { name: 'cleanup' } });
  sleep(randomIntBetween(1, 3) / 10);
}

export function handleSummary(data) {
  const p95 = data.metrics.http_req_duration?.values?.['p(95)'];
  const failed = data.metrics.http_req_failed?.values?.rate;
  const confirmed = data.metrics.checkout_confirmed?.values?.rate;
  const lines = [
    '',
    'Load test summary',
    '-----------------',
    `p95 latency:        ${p95 ? p95.toFixed(0) : 'n/a'} ms  (objective < 1500)`,
    `failed requests:    ${failed !== undefined ? (failed * 100).toFixed(2) : 'n/a'}%  (objective < 0.5%)`,
    `checkout confirmed: ${confirmed !== undefined ? (confirmed * 100).toFixed(1) : 'n/a'}%  (objective >= 95%)`,
    '',
  ];
  return { stdout: lines.join('\n') };
}
