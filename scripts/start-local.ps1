<#
.SYNOPSIS
  Runs the whole stack natively on Windows - no Docker: iam-app, account-app, order-app,
  market-data-app and the Angular frontend, against a local PostgreSQL.

.DESCRIPTION
  Builds the four services, starts each (and the frontend) in its own titled window, waits
  until they report healthy, then opens the app in the browser. Stop everything with
  .\scripts\stop-local.ps1, or close the windows.

  Needs, once: JDK 21+, Maven, Node.js, and a PostgreSQL with the paysprint database - create
  that with .\scripts\setup-windows-db.ps1.

  DB_PASSWORD and JWT_SECRET are read from .env when it exists, the same file docker compose
  uses; otherwise the services' built-in local defaults apply.

.EXAMPLE
  .\scripts\start-local.ps1
.EXAMPLE
  .\scripts\start-local.ps1 -SkipBuild          # reuse the jars from the last build
.EXAMPLE
  .\scripts\start-local.ps1 -LightHistory       # generate a month of price history, not a year
#>
[CmdletBinding()]
param(
    # Start from the jars already in */target instead of rebuilding.
    [switch]$SkipBuild,
    # On a first start against an empty database, generate about a month of synthetic price
    # history instead of a year - much quicker, at the cost of shorter charts.
    [switch]$LightHistory,
    [switch]$NoBrowser,
    [string]$DbHost = 'localhost',
    [int]$DbPort = 5432
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$runDir = Join-Path $repoRoot '.local-run'
$pidFile = Join-Path $runDir 'pids.json'

$services = @(
    @{ Name = 'iam-app';         Port = 8081 },
    @{ Name = 'account-app';     Port = 8082 },
    @{ Name = 'market-data-app'; Port = 8083 },
    @{ Name = 'order-app';       Port = 8084 }
)
$frontendPort = 4200

function Test-Port([string]$HostName, [int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $connect = $client.BeginConnect($HostName, $Port, $null, $null)
        return $connect.AsyncWaitHandle.WaitOne(1000) -and $client.Connected
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Read-DotEnv([string]$Path) {
    $values = @{}
    if (Test-Path $Path) {
        foreach ($line in Get-Content $Path) {
            if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$' -and $line -notmatch '^\s*#') {
                $values[$Matches[1]] = $Matches[2].Trim('"').Trim("'")
            }
        }
    }
    return $values
}

# ---- Preflight -------------------------------------------------------------------------------

foreach ($tool in 'java', 'mvn', 'npm') {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "'$tool' was not found on PATH. Install it (see the README) and open a new terminal."
    }
}

if (Test-Path $pidFile) {
    Write-Host 'The stack looks like it is already running (found .local-run\pids.json).' -ForegroundColor Yellow
    Write-Host 'Run .\scripts\stop-local.ps1 first, then try again.'
    exit 1
}

$busy = @($services.Port + $frontendPort | Where-Object { Test-Port 'localhost' $_ })
if ($busy.Count -gt 0) {
    throw "Port(s) $($busy -join ', ') already in use. Stop whatever is using them (docker, an earlier run, a mock server) and try again."
}

if (-not (Test-Port $DbHost $DbPort)) {
    throw "No PostgreSQL answering on ${DbHost}:${DbPort}. Start the PostgreSQL service, and run .\scripts\setup-windows-db.ps1 once if you haven't."
}

# ---- Environment for the services ------------------------------------------------------------

$dotEnv = Read-DotEnv (Join-Path $repoRoot '.env')
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://${DbHost}:${DbPort}/paysprint"
$env:SPRING_DATASOURCE_USERNAME = 'paysprint'
if ($dotEnv.DB_PASSWORD) { $env:SPRING_DATASOURCE_PASSWORD = $dotEnv.DB_PASSWORD }
# Every service must share one secret, or account, order and market data reject iam's tokens.
if ($dotEnv.JWT_SECRET) { $env:JWT_SECRET = $dotEnv.JWT_SECRET }
$env:MARKET_DATA_BASE_URL = 'http://localhost:8083'
$env:ACCOUNT_SERVICE_BASE_URL = 'http://localhost:8082'
if ($LightHistory) {
    $env:MARKETDATA_HISTORY_BACKFILL_TIERS = '86400:30,3600:7,300:2,60:1'
}

# ---- Build -----------------------------------------------------------------------------------

if (-not $SkipBuild) {
    Write-Host 'Building the services (tests skipped)...' -ForegroundColor Cyan
    Push-Location $repoRoot
    try {
        & mvn -q -B -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'Maven build failed - see the output above.' }
    } finally {
        Pop-Location
    }
}

$jars = @{}
foreach ($service in $services) {
    $jar = Get-ChildItem (Join-Path $repoRoot "$($service.Name)\target") -Filter '*.jar' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) {
        throw "No jar for $($service.Name) in $($service.Name)\target. Run without -SkipBuild."
    }
    $jars[$service.Name] = $jar.FullName
}

$frontendDir = Join-Path $repoRoot 'frontend'
if (-not (Test-Path (Join-Path $frontendDir 'node_modules\@angular\core'))) {
    Write-Host 'Installing frontend dependencies (first run only)...' -ForegroundColor Cyan
    Push-Location $frontendDir
    try {
        & npm ci --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw 'npm ci failed - see the output above.' }
    } finally {
        Pop-Location
    }
}

# ---- Start -----------------------------------------------------------------------------------

New-Item -ItemType Directory -Force $runDir | Out-Null
$started = @{}

function Start-Window([string]$Title, [string]$WorkingDir, [string]$Command) {
    # Each process gets a titled window showing its log; the window stays open if it crashes,
    # so the error is still readable.
    $script = "`$Host.UI.RawUI.WindowTitle = '$Title'; $Command; Write-Host ''; Write-Host '$Title stopped.' -ForegroundColor Yellow"
    $process = Start-Process powershell -WorkingDirectory $WorkingDir -PassThru `
        -ArgumentList '-NoLogo', '-NoExit', '-ExecutionPolicy', 'Bypass', '-Command', $script
    return $process.Id
}

foreach ($service in $services) {
    Write-Host "Starting $($service.Name) on port $($service.Port)..."
    $started[$service.Name] = Start-Window "Leapfolio - $($service.Name)" $repoRoot "java -jar '$($jars[$service.Name])'"
}
Write-Host "Starting frontend on port $frontendPort..."
$started['frontend'] = Start-Window 'Leapfolio - frontend' $frontendDir 'npm start'

$started | ConvertTo-Json | Set-Content -Encoding utf8 $pidFile

# ---- Wait until healthy ----------------------------------------------------------------------

Write-Host ''
Write-Host 'Waiting for everything to come up (a first start that generates price history can take a few minutes)...' -ForegroundColor Cyan
$deadline = (Get-Date).AddMinutes(10)
$pending = [System.Collections.Generic.List[string]]::new()
$services | ForEach-Object { $pending.Add($_.Name) }
$pending.Add('frontend')

while ($pending.Count -gt 0 -and (Get-Date) -lt $deadline) {
    foreach ($name in @($pending)) {
        $url = if ($name -eq 'frontend') { "http://localhost:$frontendPort/" }
               else { "http://localhost:$(($services | Where-Object Name -eq $name).Port)/actuator/health" }
        try {
            Invoke-WebRequest $url -UseBasicParsing -TimeoutSec 3 | Out-Null
            Write-Host "  $name is up" -ForegroundColor Green
            $pending.Remove($name) | Out-Null
        } catch {
            if (-not (Get-Process -Id $started[$name] -ErrorAction SilentlyContinue)) {
                Write-Host "  $name exited - check its window for the error." -ForegroundColor Red
                $pending.Remove($name) | Out-Null
            }
        }
    }
    if ($pending.Count -gt 0) { Start-Sleep -Seconds 3 }
}

Write-Host ''
if ($pending.Count -gt 0) {
    Write-Host "Still waiting on: $($pending -join ', '). Check their windows." -ForegroundColor Yellow
} else {
    Write-Host 'Leapfolio is running at http://localhost:4200' -ForegroundColor Green
    Write-Host 'Sign in as alice.johnson@leap.com / Password123!'
    if (-not $NoBrowser) { Start-Process "http://localhost:$frontendPort/" }
}
Write-Host 'Stop everything with: .\scripts\stop-local.ps1'
