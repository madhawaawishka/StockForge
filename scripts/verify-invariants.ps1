# Powershell script to verify business invariants via docker compose postgres or local psql
param(
    [string]$HostName = "localhost",
    [int]$Port = 5432,
    [string]$Database = "stockforge",
    [string]$User = "stockforge_admin",
    [string]$Password = "admin_local_only"
)

$ErrorActionPreference = "Stop"

Write-Host "Running StockForge Invariant Verification against $Database on $HostName:$Port..." -ForegroundColor Cyan

$env:PGPASSWORD = $Password

$cmd = "docker compose exec -T postgres psql -U $User -d $Database -f - < ./scripts/check-invariants.sql"
if (-not (Get-Command "docker" -ErrorAction SilentlyContinue)) {
    $cmd = "psql -h $HostName -p $Port -U $User -d $Database -f ./scripts/check-invariants.sql"
}

try {
    Invoke-Expression $cmd
    Write-Host "`nInvariant check completed successfully. Ensure all query tables above return (0 rows)." -ForegroundColor Green
} catch {
    Write-Host "`nError executing invariant verification: $_" -ForegroundColor Red
    exit 1
}
