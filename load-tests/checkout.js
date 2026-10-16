// Full checkout under load (spec §26 scenario 2): reserve -> order -> asynchronous payment -> terminal order status.
// A share of payments is made to fail, so compensation (stock release) runs under load too.
//
//   k6 run -e PEAK_RATE=50 load-tests/checkout.js
//   ./scripts/verify-invariants.sh        (afterwards: orders, payments and stock must agree)
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { jsonHeaders, token } from './lib/auth.js';
import { BASE_URL, createProduct, uuid } from './lib/helpers.js';

const PEAK_RATE = parseInt(__ENV.PEAK_RATE || '50', 10);
const FAILED_PAYMENT_SHARE = parseFloat(__ENV.FAILED_PAYMENT_SHARE || '0.1');

const confirmed = new Counter('checkouts_confirmed');
const compensated = new Counter('checkouts_compensated');
const unfinished = new Counter('checkouts_unfinished');
const checkoutDuration = new Trend('checkout_to_terminal_state', true);

export const options = {
  scenarios: {
    checkout: {
      executor: 'ramping-arrival-rate',
      startRate: 1,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 1000,
      stages: [
        { duration: '30s', target: PEAK_RATE },
        { duration: '2m', target: PEAK_RATE },
        { duration: '15s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checkouts_unfinished: ['count==0'],
    checkout_to_terminal_state: ['p(95)<3000'],
    'http_req_duration{name:POST /api/v1/reservations}': ['p(99)<300'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  return { productId: createProduct(1_000_000) };
}

export default function ({ productId }) {
  const shopper = token(uuid());
  const started = Date.now();

  const reserved = http.post(`${BASE_URL}/api/v1/reservations`, JSON.stringify({ productId, quantity: 1 }), {
    headers: jsonHeaders(shopper, { 'Idempotency-Key': uuid() }),
    tags: { name: 'POST /api/v1/reservations' },
  });
  if (!check(reserved, { 'reserved': (r) => r.status === 201 })) {
    return;
  }

  const outcome = Math.random() < FAILED_PAYMENT_SHARE ? 'FAILED' : 'SUCCEEDED';
  const placed = http.post(
    `${BASE_URL}/api/v1/orders`,
    JSON.stringify({ reservationId: reserved.json('id') }),
    {
      headers: jsonHeaders(shopper, { 'Idempotency-Key': uuid(), 'X-Payment-Simulation': outcome }),
      tags: { name: 'POST /api/v1/orders' },
    },
  );
  if (!check(placed, { 'order accepted': (r) => r.status === 202 })) {
    return;
  }

  const orderUrl = `${BASE_URL}/api/v1/orders/${placed.json('id')}`;
  let status = placed.json('status');
  for (let attempt = 0; attempt < 80 && status === 'PENDING_PAYMENT'; attempt++) {
    sleep(0.25);
    status = http.get(orderUrl, { headers: jsonHeaders(shopper), tags: { name: 'GET /api/v1/orders/{id}' } })
      .json('status');
  }
  checkoutDuration.add(Date.now() - started);

  if (status === 'CONFIRMED') {
    confirmed.add(1);
  } else if (status === 'CANCELLED') {
    compensated.add(1);
  } else {
    unfinished.add(1, { status: String(status) });
  }
  check(status, {
    'the payment outcome decides the order': (s) =>
      (outcome === 'SUCCEEDED' && s === 'CONFIRMED') || (outcome === 'FAILED' && s === 'CANCELLED'),
  });
}
