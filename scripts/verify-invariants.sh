#!/usr/bin/env bash
# Runs scripts/check-invariants.sql against the local stack's database and exits non-zero on any violation.
# Uses the PostgreSQL container of `docker compose` when it is running, otherwise a local psql client.
set -euo pipefail

cd "$(dirname "$0")/.."

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-stockforge}"
DB_USER="${DB_USER:-stockforge_admin}"
# Local-only credential of the docker compose stack (not a secret).
export PGPASSWORD="${DB_PASSWORD:-admin_local_only}"

echo "Checking StockForge invariants in ${DB_NAME}..."
if docker compose ps --status running --services 2>/dev/null | grep -qx postgres; then
    docker compose exec -T -e PGPASSWORD postgres \
        psql --no-psqlrc --quiet -U "${DB_USER}" -d "${DB_NAME}" -f - < scripts/check-invariants.sql
else
    psql --no-psqlrc --quiet -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}" \
        -f scripts/check-invariants.sql
fi
