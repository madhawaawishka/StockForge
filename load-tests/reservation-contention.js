import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { createToken, authHeaders } from './lib/auth.js';
import { getBaseUrl, randomUuid } from './lib/helpers.js';

// Custom Metrics
const successfulReservations = new Counter('successful_reservations');
const insufficientStockResponses = new Counter('insufficient_stock_responses');
const unexpectedErrors = new Counter('unexpected_errors');
const reservationLatency = new Trend('reservation_latency_ms');

export const options = {
  scenarios: {
    flash_sale: {
      executor: 'per-vu-iterations',
      vus: 2000,
      iterations: 1,
      maxDuration: '1m'
    }
  },
  thresholds: {
    'reservation_latency_ms': ['p(99)<50'], // SLO-1: P99 < 50ms
    'unexpected_errors': ['count==0'],       // Zero unexpected errors
    'http_req_failed': ['rate<0.96']         // Allow expected 409s (95% of 2000), but reject 5xx
  }
};

export default function() {
  const baseUrl = getBaseUrl();
  const vuId = __VU;
  const userId = `018f6f5a-3d2b-7000-8000-${vuId.toString().padStart(12, '0')}`;
  const token = createToken(userId);
  const headers = authHeaders(token);
  
  // Idempotency key per user
  const idempotencyKey = randomUuid();
  headers['Idempotency-Key'] = idempotencyKey;

  // Single Hot SKU target (seeded or configured via env)
  const productId = __ENV.HOT_PRODUCT_ID || '018f6f5a-3d2b-7000-8000-000000000100';

  const payload = JSON.stringify({
    productId: productId,
    quantity: 1
  });

  const startTime = Date.now();
  const res = http.post(`${baseUrl}/api/v1/reservations`, payload, { headers });
  reservationLatency.add(Date.now() - startTime);

  if (res.status === 201) {
    successfulReservations.add(1);
    check(res, {
      'reservation created with PENDING status': (r) => r.json('status') === 'PENDING'
    });
  } else if (res.status === 409) {
    insufficientStockResponses.add(1);
    check(res, {
      'rejected with insufficient stock': (r) => r.body.includes('insufficient-stock')
    });
  } else {
    unexpectedErrors.add(1);
    check(res, {
      'unexpected response code': () => false
    });
  }
}
