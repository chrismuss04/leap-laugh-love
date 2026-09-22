<#
.SYNOPSIS
  Creates and seeds the paysprint database on a local Windows PostgreSQL install.

.DESCRIPTION
  Does, once, what the bundled "db" container's entrypoint does on its first start: creates the
  paysprint role and database, then loads the schema and seed files from iam-app (the same
  copies docker-compose.yml mounts). Use it with docker-compose.external-db.yml.

  Asks for the PostgreSQL superuser's password; it is held only in this PowerShell session.

  The paysprint user's password is taken from DB_PASSWORD in .env (the same value
  start-local.ps1 and docker compose use), falling back to 'changeme'. It is set on every run,
  so re-running after changing DB_PASSWORD brings the database back in step.

  An existing paysprint database's data is left alone unless -Reset is passed, because the seed
  files are not safe to run twice.

.EXAMPLE
  .\scripts\setup-windows-db.ps1
.EXAMPLE
  .\scripts\setup-windows-db.ps1 -Reset            # drop and rebuild the database
.EXAMPLE
  .\scripts\setup-windows-db.ps1 -PsqlPath 'C:\Program Files\PostgreSQL\16\bin\psql.exe'
#>
[CmdletBinding()]
param(
    [string]$PsqlPath,
    [string]$SuperUser = 'postgres',
    [string]$DbHost = 'localhost',
    [int]$Port = 5432,
    # The paysprint user's password. Defaults to DB_PASSWORD from .env, else 'changeme'.
    [string]$AppPassword,
    [switch]$Reset
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$dbDir = Join-Path $repoRoot 'iam-app\src\main\resources\db'
# Same files, same order as the bundled container's /docker-entrypoint-initdb.d.
$scripts = @(
    'leap_laugh_love_schema.sql',
    'seed_iam.sql',
    'seed_trading.sql',
    'seed_marketdata.sql',
    'seed_sp500_marketdata.sql',
    'seed_sp500_trading.sql'
)

if (-not $PsqlPath) {
    $PsqlPath = Get-ChildItem 'C:\Program Files\PostgreSQL\*\bin\psql.exe' -ErrorAction SilentlyContinue |
        Sort-Object { [int]$_.Directory.Parent.Name } -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $PsqlPath -or -not (Test-Path $PsqlPath)) {
    throw 'psql.exe not found. Pass -PsqlPath with the full path to your PostgreSQL bin\psql.exe.'
}

if (-not $AppPassword) {
    $envFile = Join-Path $repoRoot '.env'
    $line = if (Test-Path $envFile) { Get-Content $envFile | Where-Object { $_ -match '^\s*DB_PASSWORD\s*=' } | Select-Object -First 1 }
    $AppPassword = if ($line) { ($line -split '=', 2)[1].Trim().Trim('"').Trim("'") } else { 'changeme' }
    Write-Host ("Using the paysprint password from " + $(if ($line) { '.env (DB_PASSWORD)' } else { "the default 'changeme'" }))
}

$secure = Read-Host "Password for PostgreSQL user '$SuperUser'" -AsSecureString
$env:PGPASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
    [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
$env:PGCLIENTENCODING = 'UTF8'

function Invoke-Psql([string]$Database, [string[]]$Arguments) {
    & $PsqlPath -h $DbHost -p $Port -U $SuperUser -d $Database -v ON_ERROR_STOP=1 -X -q @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "psql failed (exit $LASTEXITCODE)"
    }
}

try {
    $exists = & $PsqlPath -h $DbHost -p $Port -U $SuperUser -d postgres -X -At `
        -c "SELECT 1 FROM pg_database WHERE datname = 'paysprint'"
    if ($LASTEXITCODE -ne 0) {
        throw "Could not connect to PostgreSQL at ${DbHost}:${Port} as '$SuperUser'."
    }

    # Create or re-password the role on every run, so the database always accepts DB_PASSWORD.
    $escapedPassword = $AppPassword.Replace("'", "''")
    Write-Host "Setting up role 'paysprint'..."
    Invoke-Psql 'postgres' @('-c', @"
DO `$`$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'paysprint') THEN
    CREATE ROLE paysprint LOGIN PASSWORD '$escapedPassword';
  ELSE
    ALTER ROLE paysprint WITH LOGIN PASSWORD '$escapedPassword';
  END IF;
END `$`$;
"@)

    if ($exists -eq '1' -and -not $Reset) {
        Write-Host "Database 'paysprint' already exists - kept its data and updated the paysprint password. Re-run with -Reset to rebuild it." -ForegroundColor Green
        return
    }
    if ($exists -eq '1') {
        Write-Host "Dropping existing 'paysprint' database..."
        Invoke-Psql 'postgres' @('-c', 'DROP DATABASE paysprint WITH (FORCE)')
    }

    Write-Host "Creating database 'paysprint'..."
    Invoke-Psql 'postgres' @('-c', 'CREATE DATABASE paysprint OWNER paysprint')

    # Load as paysprint so every table it creates is owned by the role the services use. The
    # schema's CREATE EXTENSION pgcrypto is allowed for a database owner (it is a trusted
    # extension), so no superuser is needed from here on.
    $env:PGPASSWORD = $AppPassword
    foreach ($file in $scripts) {
        Write-Host "Loading $file..."
        & $PsqlPath -h $DbHost -p $Port -U paysprint -d paysprint -v ON_ERROR_STOP=1 -X -q -f (Join-Path $dbDir $file)
        if ($LASTEXITCODE -ne 0) {
            throw "Loading $file failed (exit $LASTEXITCODE)"
        }
    }

    Write-Host "Done. 'paysprint' is ready on ${DbHost}:${Port}." -ForegroundColor Green
    Write-Host 'If the services run in a VM, also allow it through pg_hba.conf and the firewall - see the README.'
}
finally {
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
}
