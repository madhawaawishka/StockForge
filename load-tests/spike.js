// Traffic spike (spec §26 scenario 3): a sudden jump to many times the normal rate on catalog reads and
// reservations. Pass criteria: the system sheds load deliberately (429 rate limited, 503 + Retry-After) instead of
// failing (no other 5xx, no timeouts), and recovers once the spike is over.
//
//   k6 run -e PEAK_RATE=1000 load-tests/spike.js
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { jsonHeaders, token } from './lib/auth.js';
import { BASE_URL, createProduct, uuid } from './lib/helpers.js';

const PEAK_RATE = parseInt(__ENV.PEAK_RATE || '1000', 10);
const shed = new Counter('requests_shed');
const broken = new Counter('requests_broken');

export const options = {
  scenarios: {
    spike: {
      executor: 'ramping-arrival-rate',
      startRate: 50,
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: 3000,
      stages: [
        { duration: '20s', target: 50 },
        { duration: '5s', target: PEAK_RATE },
        { duration: '40s', target: PEAK_RATE },
        { duration: '5s', target: 50 },
        { duration: '30s', target: 50 },
      ],
    },
  },
  thresholds: {
    // Shedding is a correct answer under a spike; anything else (500, 502, 504, timeouts) is not.
    requests_broken: ['count==0'],
    'http_req_duration{expected_response:true}': ['p(99)<1000'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  return { productId: createProduct(1_000_000) };
}

export default function ({ productId }) {
  const read = Math.random() < 0.8;
  const res = read
    ? http.get(`${BASE_URL}/api/v1/products/${productId}/availability`, {
        tags: { name: 'GET availability' },
        responseCallback: http.expectedStatuses(200),
      })
    : http.post(`${BASE_URL}/api/v1/reservations`, JSON.stringify({ productId, quantity: 1 }), {
        headers: jsonHeaders(token(uuid()), { 'Idempotency-Key': uuid() }),
        tags: { name: 'POST /api/v1/reservations' },
        responseCallback: http.expectedStatuses(201),
      });
  if (res.status === 429 || res.status === 503) {
    shed.add(1);
    check(res, { 'shed responses say when to retry': (r) => !!r.headers['Retry-After'] });
  } else if (res.status === 0 || res.status >= 500) {
    broken.add(1, { status: String(res.status) });
  }
}
