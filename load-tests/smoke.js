// End-to-end smoke test of the running system through the gateway (CI compose job, spec §29-30):
// catalog read -> reservation (and its idempotent replay) -> order -> asynchronous payment -> CONFIRMED,
// plus the security checks a deployment must never regress (401 without a token, 404 for another user's order).
//
//   k6 run load-tests/smoke.js            (stack: docker compose --profile apps up -d --build)
import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { jsonHeaders, token } from './lib/auth.js';
import { BASE_URL, createProduct, uuid } from './lib/helpers.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    checks: ['rate==1'],
  },
};

export function setup() {
  return { productId: createProduct(5) };
}

export default function ({ productId }) {
  const shopper = token(uuid());
  const product = http.get(`${BASE_URL}/api/v1/products/${productId}`);
  check(product, {
    'catalog read is public': (r) => r.status === 200,
    'catalog read carries an ETag': (r) => !!r.headers['Etag'],
  });

  const unauthenticated = http.post(
    `${BASE_URL}/api/v1/reservations`,
    JSON.stringify({ productId, quantity: 1 }),
    { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': uuid() } },
  );
  check(unauthenticated, {
    'writes require a token (401 problem+json)': (r) =>
      r.status === 401 && r.headers['Content-Type'].startsWith('application/problem+json'),
  });

  const key = uuid();
  const body = JSON.stringify({ productId, quantity: 1 });
  const reserved = http.post(`${BASE_URL}/api/v1/reservations`, body, {
    headers: jsonHeaders(shopper, { 'Idempotency-Key': key }),
  });
  if (!check(reserved, { 'reservation created': (r) => r.status === 201 })) {
    fail(`reservation failed: ${reserved.status} ${reserved.body}`);
  }
  const replay = http.post(`${BASE_URL}/api/v1/reservations`, body, {
    headers: jsonHeaders(shopper, { 'Idempotency-Key': key }),
  });
  check(replay, {
    'a retry with the same key replays the original result': (r) =>
      r.status === 201 && r.json('id') === reserved.json('id') && r.headers['Idempotent-Replayed'] === 'true',
  });

  const placed = http.post(
    `${BASE_URL}/api/v1/orders`,
    JSON.stringify({ reservationId: reserved.json('id') }),
    { headers: jsonHeaders(shopper, { 'Idempotency-Key': uuid(), 'X-Payment-Simulation': 'SUCCEEDED' }) },
  );
  if (!check(placed, { 'order accepted (202)': (r) => r.status === 202 })) {
    fail(`order failed: ${placed.status} ${placed.body}`);
  }
  const orderId = placed.json('id');

  let status = placed.json('status');
  for (let attempt = 0; attempt < 60 && status === 'PENDING_PAYMENT'; attempt++) {
    sleep(0.5);
    status = http.get(`${BASE_URL}/api/v1/orders/${orderId}`, { headers: jsonHeaders(shopper) }).json('status');
  }
  check(status, { 'the saga confirms the order': (s) => s === 'CONFIRMED' });

  const someoneElse = http.get(`${BASE_URL}/api/v1/orders/${orderId}`, { headers: jsonHeaders(token(uuid())) });
  check(someoneElse, { "another user's order is not found (BOLA)": (r) => r.status === 404 });
}
