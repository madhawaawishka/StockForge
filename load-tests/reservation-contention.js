// Flash sale on one hot product (spec §26 scenario 1): far more buyers than units, all arriving at once.
// Pass criteria: exactly STOCK reservations succeed (no oversell, no undersell), every other buyer gets a clean
// 409 "insufficient stock", there are no 5xx, and successful reservations meet the 300 ms P99 objective (NFR-3).
//
//   k6 run -e STOCK=100 -e BUYERS=2000 -e RATE=500 load-tests/reservation-contention.js
//   ./scripts/verify-invariants.sh        (afterwards: database-level proof)
//
// Every request uses a new user id, so neither the per-user rate limit nor the purchase limit interferes: the only
// thing standing between the buyers and overselling is the reservation strategy.
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { jsonHeaders, token } from './lib/auth.js';
import { BASE_URL, createProduct, uuid } from './lib/helpers.js';

const STOCK = parseInt(__ENV.STOCK || '100', 10);
const BUYERS = parseInt(__ENV.BUYERS || '2000', 10);
const RATE = parseInt(__ENV.RATE || '500', 10);

const reserved = new Counter('reservations_succeeded');
const soldOut = new Counter('reservations_sold_out');
const unexpected = new Counter('reservations_unexpected');
// Latency per outcome: a k6 request tag cannot depend on the response, so the outcome trends are recorded here.
const reservedLatency = new Trend('latency_reserved', true);
const soldOutLatency = new Trend('latency_sold_out', true);

export const options = {
  scenarios: {
    flash_sale: {
      // Open model: requests keep arriving at RATE/s even if the system slows down (no coordinated omission).
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: `${Math.ceil(BUYERS / RATE)}s`,
      preAllocatedVUs: Math.min(RATE, 500),
      maxVUs: 2000,
    },
  },
  thresholds: Object.assign(
    {
      reservations_succeeded: [`count==${STOCK}`],
      reservations_unexpected: ['count==0'],
    },
    // Latency objectives only mean something on a warmed-up system (docs/benchmarks/README.md). CI runs this scenario
    // right after start-up to prove correctness, and sets CORRECTNESS_ONLY=true.
    __ENV.CORRECTNESS_ONLY === 'true' ? {} : { latency_reserved: ['p(99)<300'], latency_sold_out: ['p(99)<300'] },
  ),
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  return { productId: createProduct(STOCK) };
}

export default function ({ productId }) {
  const res = http.post(`${BASE_URL}/api/v1/reservations`, JSON.stringify({ productId, quantity: 1 }), {
    headers: jsonHeaders(token(uuid()), { 'Idempotency-Key': uuid() }),
    tags: { name: 'POST /api/v1/reservations' },
    responseCallback: http.expectedStatuses(201, 409),
  });
  if (res.status === 201) {
    reserved.add(1);
    reservedLatency.add(res.timings.duration);
  } else if (res.status === 409) {
    soldOut.add(1);
    soldOutLatency.add(res.timings.duration);
    check(res, { 'sold out is reported as insufficient-stock': (r) => r.body.includes('insufficient-stock') });
  } else {
    unexpected.add(1, { status: String(res.status) });
  }
}
