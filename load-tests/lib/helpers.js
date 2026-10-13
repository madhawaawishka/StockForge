/**
 * Generates a random UUID string (RFC 4122 v4 equivalent) for idempotency keys.
 */
export function randomUuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
    const r = Math.random() * 16 | 0;
    const v = c === 'x' ? r : (r & 0x3 | 0x8);
    return v.toString(16);
  });
}

/**
 * Returns default base URL for local or Docker testing.
 */
export function getBaseUrl() {
  return __ENV.BASE_URL || 'http://localhost:8080';
}
