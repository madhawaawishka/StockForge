import http from 'k6/http';
import { check, sleep } from 'k6';
import { createToken, authHeaders } from './lib/auth.js';
import { getBaseUrl } from './lib/helpers.js';

export const options = {
  stages: [
    { duration: '10s', target: 100 },
    { duration: '5s', target: 2000 },  // Instant surge spike
    { duration: '30s', target: 2000 }, // Sustain spike
    { duration: '10s', target: 100 },  // Cool down
    { duration: '10s', target: 0 }
  ],
  thresholds: {
    // Under extreme spike, rate limiting (429) or degradation is acceptable, but system must not crash (5xx < 2%)
    'http_req_failed{status:500}': ['rate<0.02']
  }
};

export default function() {
  const baseUrl = getBaseUrl();
  const token = createToken(`user-${__VU}`);
  const headers = authHeaders(token);

  const res = http.get(`${baseUrl}/api/v1/products?limit=5`, { headers });
  check(res, {
    'successful or rate limited response': (r) => r.status === 200 || r.status === 429
  });

  sleep(0.1);
}
