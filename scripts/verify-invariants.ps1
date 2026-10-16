# Runs scripts/check-invariants.sql against the local stack's database; exits non-zero on any violation.
# Windows counterpart of verify-invariants.sh. Usage: .\scripts\verify-invariants.ps1
param(
    [string]$Database = "stockforge",
    [string]$User = "stockforge_admin",
    # Local-only credential of the docker compose stack (not a secret).
    [string]$Password = "admin_local_only"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$sql = Join-Path $root "scripts\check-invariants.sql"

Write-Host "Checking StockForge invariants in $Database..."
# PowerShell has no '<' redirection: pipe the file into psql inside the PostgreSQL container instead.
Get-Content -Raw -Encoding UTF8 $sql |
    docker compose --project-directory $root exec -T -e "PGPASSWORD=$Password" postgres `
        psql --no-psqlrc --quiet -U $User -d $Database -f -
if ($LASTEXITCODE -ne 0) {
    Write-Host "Invariant violations found (or the check could not run)." -ForegroundColor Red
    exit $LASTEXITCODE
}
Write-Host "All invariants hold." -ForegroundColor Green
