import http from 'k6/http';
import { check, fail } from 'k6';
import { adminToken, jsonHeaders } from './auth.js';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

/** RFC 4122 version-4 UUID, used for user ids and idempotency keys. */
export function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

/** Creates a product with the given stock through the admin API and returns its id (call from setup()). */
export function createProduct(stock, price = '19.99') {
  const sku = `K6-${uuid().slice(0, 13).toUpperCase()}`;
  const res = http.post(
    `${BASE_URL}/api/v1/admin/products`,
    JSON.stringify({ sku, name: `Load test ${sku}`, price: { amount: price, currency: 'USD' }, initialStock: stock }),
    { headers: jsonHeaders(adminToken()), tags: { name: 'setup' } },
  );
  if (!check(res, { 'product created': (r) => r.status === 201 })) {
    fail(`could not create the test product: ${res.status} ${res.body}`);
  }
  return res.json('id');
}

/** Reads the authoritative stock counters (approximate=false comes from the database). */
export function availability(productId) {
  return http.get(`${BASE_URL}/api/v1/products/${productId}/availability`, { tags: { name: 'setup' } }).json();
}
