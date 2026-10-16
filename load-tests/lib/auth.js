import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

// The local stack's HMAC key (application-local.yml, docker-compose.yml). It is not a secret; any other environment
// passes its own with -e JWT_SECRET=...
const SECRET = __ENV.JWT_SECRET || 'stockforge-local-development-only-hmac-key';
const ISSUER = __ENV.JWT_ISSUER || 'stockforge-auth';
const AUDIENCE = __ENV.JWT_AUDIENCE || 'stockforge-api';

/**
 * Signs an HS256 access token the gateway accepts: issuer, audience, a UUID subject and the roles claim
 * (services/api-gateway WebSecurityConfiguration). Tokens live for an hour, longer than any scenario.
 */
export function token(userId, roles = ['CUSTOMER']) {
  const now = Math.floor(Date.now() / 1000);
  const header = encoding.b64encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' }), 'rawurl');
  const payload = encoding.b64encode(
    JSON.stringify({ iss: ISSUER, aud: [AUDIENCE], sub: userId, roles, iat: now, exp: now + 3600 }),
    'rawurl',
  );
  const signature = crypto.hmac('sha256', SECRET, `${header}.${payload}`, 'base64rawurl');
  return `${header}.${payload}.${signature}`;
}

export function adminToken() {
  return token('00000000-0000-7000-8000-00000000ad01', ['ADMIN']);
}

export function jsonHeaders(bearer, extra = {}) {
  return Object.assign(
    { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json', Accept: 'application/json' },
    extra,
  );
}
