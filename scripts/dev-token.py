#!/usr/bin/env python3
"""Prints a gateway access token for the local stack (HS256, signed with the local-only development key).

    python scripts/dev-token.py                     # a customer with a random user id
    python scripts/dev-token.py --admin             # an administrator
    python scripts/dev-token.py --user 0190c3b2-... --ttl 7200

    TOKEN=$(python scripts/dev-token.py --admin)
    curl -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
         -d '{"sku":"DEMO-1","name":"Demo","price":{"amount":"9.99","currency":"USD"},"initialStock":10}' \
         http://localhost:8080/api/v1/admin/products

There is no identity provider in this project (spec §28 keeps authentication out of scope); real deployments verify
tokens from one. Only the standard library is used, so the script runs anywhere Python 3 does.
"""
import argparse
import base64
import hashlib
import hmac
import json
import os
import time
import uuid

LOCAL_SECRET = "stockforge-local-development-only-hmac-key"  # same value as application-local.yml; not a secret


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--user", default=str(uuid.uuid4()), help="subject (a UUID); random by default")
    parser.add_argument("--admin", action="store_true", help="grant the ADMIN role instead of CUSTOMER")
    parser.add_argument("--ttl", type=int, default=3600, help="lifetime in seconds")
    args = parser.parse_args()

    uuid.UUID(args.user)  # the gateway rejects non-UUID subjects; fail here with a clear error instead
    now = int(time.time())
    claims = {
        "iss": os.environ.get("JWT_ISSUER", "stockforge-auth"),
        "aud": [os.environ.get("JWT_AUDIENCE", "stockforge-api")],
        "sub": args.user,
        "roles": ["ADMIN"] if args.admin else ["CUSTOMER"],
        "iat": now,
        "exp": now + args.ttl,
    }
    header = b64url(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    payload = b64url(json.dumps(claims, separators=(",", ":")).encode())
    secret = os.environ.get("JWT_SECRET", LOCAL_SECRET).encode()
    signature = b64url(hmac.new(secret, f"{header}.{payload}".encode(), hashlib.sha256).digest())
    print(f"{header}.{payload}.{signature}")


if __name__ == "__main__":
    main()
