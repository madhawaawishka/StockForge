import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { createToken, authHeaders } from './lib/auth.js';
import { getBaseUrl, randomUuid } from './lib/helpers.js';

const completedCheckouts = new Counter('completed_checkouts');
const checkoutE2eDuration = new Trend('checkout_e2e_duration_ms');

export const options = {
  stages: [
    { duration: '30s', target: 50 },
    { duration: '1m', target: 200 },
    { duration: '30s', target: 0 }
  ],
  thresholds: {
    'checkout_e2e_duration_ms': ['p(95)<3000'],
    'http_req_failed': ['rate<0.05']
  }
};

export default function() {
  const baseUrl = getBaseUrl();
  const userId = `018f6f5a-3d2b-7000-8000-${__VU.toString().padStart(12, '0')}`;
  const token = createToken(userId);
  const headers = authHeaders(token);
  const productId = __ENV.PRODUCT_ID || '018f6f5a-3d2b-7000-8000-000000000100';

  const e2eStart = Date.now();

  // 1. Reserve Stock
  headers['Idempotency-Key'] = randomUuid();
  const reservePayload = JSON.stringify({ productId, quantity: 1 });
  const reserveRes = http.post(`${baseUrl}/api/v1/reservations`, reservePayload, { headers });

  if (reserveRes.status !== 201) {
    sleep(1);
    return;
  }

  const reservationId = reserveRes.json('reservationId');

  // 2. Create Order
  headers['Idempotency-Key'] = randomUuid();
  const orderPayload = JSON.stringify({
    reservationId: reservationId,
    items: [{ productId: productId, quantity: 1, unitPrice: 29.99, currency: 'USD' }]
  });
  const orderRes = http.post(`${baseUrl}/api/v1/orders`, orderPayload, { headers });

  if (orderRes.status === 201) {
    completedCheckouts.add(1);
    checkoutE2eDuration.add(Date.now() - e2eStart);
    check(orderRes, {
      'order created': (r) => r.json('orderId') !== undefined
    });
  }

  sleep(1);
}
