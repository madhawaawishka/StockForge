// Soak (spec §26 scenario 4): steady, moderate traffic for a long time to expose leaks — connections, memory,
// threads, an outbox or expiry backlog that grows slowly. Reservations are cancelled again so stock never runs out.
// Watch the Grafana dashboards while it runs; afterwards run ./scripts/verify-invariants.sh.
//
//   k6 run -e RATE=100 -e DURATION=30m load-tests/soak.js
import http from 'k6/http';
import { check } from 'k6';
import { jsonHeaders, token } from './lib/auth.js';
import { BASE_URL, createProduct, uuid } from './lib/helpers.js';

const RATE = parseInt(__ENV.RATE || '100', 10);
const DURATION = __ENV.DURATION || '30m';

export const options = {
  scenarios: {
    soak: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 50,
      maxVUs: 500,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.001'],
    'http_req_duration{name:POST /api/v1/reservations}': ['p(99)<300'],
    'http_req_duration{name:GET product}': ['p(99)<100'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  return { productIds: [createProduct(10_000), createProduct(10_000), createProduct(10_000)] };
}

export default function ({ productIds }) {
  const productId = productIds[Math.floor(Math.random() * productIds.length)];
  if (Math.random() < 0.8) {
    const res = http.get(`${BASE_URL}/api/v1/products/${productId}`, { tags: { name: 'GET product' } });
    check(res, { 'product read': (r) => r.status === 200 });
    return;
  }
  const shopper = token(uuid());
  const reserved = http.post(`${BASE_URL}/api/v1/reservations`, JSON.stringify({ productId, quantity: 1 }), {
    headers: jsonHeaders(shopper, { 'Idempotency-Key': uuid() }),
    tags: { name: 'POST /api/v1/reservations' },
  });
  if (check(reserved, { 'reserved': (r) => r.status === 201 })) {
    const cancelled = http.post(`${BASE_URL}/api/v1/reservations/${reserved.json('id')}/cancel`, null, {
      headers: jsonHeaders(shopper),
      tags: { name: 'POST /api/v1/reservations/{id}/cancel' },
    });
    check(cancelled, { 'cancelled': (r) => r.status === 200 });
  }
}
