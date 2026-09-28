<#
.SYNOPSIS
  Loads the E2E test users (e2e/seed/e2e_seed.sql) into a native Windows PostgreSQL - the one
  scripts/setup-windows-db.ps1 creates. Docker users don't need this: docker-compose.e2e.yml
  mounts the same file into a fresh database volume.

.DESCRIPTION
  Safe to re-run: the seed skips anything that already exists and never double-funds an account.
  Uses DB_PASSWORD from the repo's .env when present, otherwise 'changeme' - the same default the
  services use.

.EXAMPLE
  .\e2e\scripts\seed-local.ps1
#>
[CmdletBinding()]
param(
    [string]$DbHost = 'localhost',
    [int]$DbPort = 5432,
    [string]$Database = 'paysprint',
    [string]$User = 'paysprint'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$seed = Join-Path $PSScriptRoot '..\seed\e2e_seed.sql'

$password = 'changeme'
$envFile = Join-Path $repoRoot '.env'
if (Test-Path $envFile) {
    foreach ($line in Get-Content $envFile) {
        if ($line -match '^\s*DB_PASSWORD\s*=\s*(.*?)\s*$') {
            $password = $Matches[1].Trim('"').Trim("'")
        }
    }
}

$psql = Get-Command psql -ErrorAction SilentlyContinue
if (-not $psql) {
    $candidate = Get-ChildItem 'C:\Program Files\PostgreSQL\*\bin\psql.exe' -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $candidate) {
        throw 'psql was not found on PATH or under C:\Program Files\PostgreSQL.'
    }
    $psql = $candidate.FullName
} else {
    $psql = $psql.Source
}

$env:PGPASSWORD = $password
try {
    & $psql -h $DbHost -p $DbPort -U $User -d $Database -v ON_ERROR_STOP=1 -q -f $seed
    if ($LASTEXITCODE -ne 0) {
        throw "psql exited with $LASTEXITCODE"
    }
} finally {
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
}
Write-Host 'E2E users loaded: e2e.trader.00-15, e2e.multi, e2e.history, e2e.noaccount (@leap.test).'
