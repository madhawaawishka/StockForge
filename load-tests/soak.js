import http from 'k6/http';
import { check, sleep } from 'k6';
import { createToken, authHeaders } from './lib/auth.js';
import { getBaseUrl } from './lib/helpers.js';

export const options = {
  stages: [
    { duration: '1m', target: 50 },
    { duration: '10m', target: 50 }, // Sustained load soak
    { duration: '1m', target: 0 }
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<150']
  }
};

export default function() {
  const baseUrl = getBaseUrl();
  const token = createToken(`soak-user-${__VU}`);
  const headers = authHeaders(token);

  const res = http.get(`${baseUrl}/api/v1/products?limit=20`, { headers });
  check(res, {
    'status is 200': (r) => r.status === 200
  });

  sleep(1);
}
