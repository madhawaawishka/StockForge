import http from 'k6/http';
import { check, sleep } from 'k6';
import { createToken, authHeaders } from './lib/auth.js';
import { getBaseUrl } from './lib/helpers.js';

export const options = {
  vus: 1,
  duration: '15s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<100']
  }
};

export default function() {
  const baseUrl = getBaseUrl();
  const token = createToken('018f6f5a-3d2b-7000-8000-000000000001');
  const headers = authHeaders(token);

  // 1. List products
  const listRes = http.get(`${baseUrl}/api/v1/products?limit=10`, { headers });
  check(listRes, {
    'list products returns 200': (r) => r.status === 200,
    'products body contains items or array': (r) => r.body.includes('products') || r.body.includes('[')
  });

  sleep(1);
}
