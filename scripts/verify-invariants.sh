#!/usr/bin/env bash
# Shell script to verify business invariants via docker compose or psql

set -euo pipefail

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-stockforge}"
DB_USER="${DB_USER:-stockforge_admin}"
export PGPASSWORD="${DB_PASSWORD:-admin_local_only}"

echo "Running StockForge Invariant Verification against ${DB_NAME} on ${DB_HOST}:${DB_PORT}..."

if command -v docker &> /dev/null && docker compose ps postgres | grep -q "Up"; then
    docker compose exec -T postgres psql -U "${DB_USER}" -d "${DB_NAME}" < ./scripts/check-invariants.sql
else
    psql -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}" -f ./scripts/check-invariants.sql
fi

echo "Invariant check completed. Verify all sections returned 0 violation rows."
