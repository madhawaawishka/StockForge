import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

const DEFAULT_SECRET = 'default-secret-key-for-development-must-be-32-bytes!';

/**
 * Creates an HMAC-SHA256 signed JWT token for k6 test scenarios.
 * Compatible with Spring Security OAuth2 Resource Server.
 */
export function createToken(userId = '018f6f5a-3d2b-7000-8000-000000000001', role = 'CUSTOMER', secret = DEFAULT_SECRET) {
  const header = {
    alg: 'HS256',
    typ: 'JWT'
  };

  const now = Math.floor(Date.now() / 1000);
  const payload = {
    sub: userId,
    userId: userId,
    roles: [role],
    iat: now,
    exp: now + 3600
  };

  const headerB64 = encoding.b64encode(JSON.stringify(header), 'rawurl');
  const payloadB64 = encoding.b64encode(JSON.stringify(payload), 'rawurl');
  const message = `${headerB64}.${payloadB64}`;

  const signature = crypto.hmac('sha256', secret, message, 'base64rawurl');
  return `${message}.${signature}`;
}

export function authHeaders(token) {
  return {
    'Authorization': `Bearer ${token}`,
    'Content-Type': 'application/json'
  };
}
