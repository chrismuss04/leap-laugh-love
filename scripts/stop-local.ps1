<#
.SYNOPSIS
  Stops everything .\scripts\start-local.ps1 started.

.DESCRIPTION
  Ends each window start-local.ps1 opened, along with the java/node process running in it.
  Anything still holding the stack's ports (8081-8083, 4200) afterwards is only reported, not
  killed, since it wasn't started by start-local.ps1.
#>
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $repoRoot '.local-run\pids.json'

if (Test-Path $pidFile) {
    $started = Get-Content $pidFile -Raw | ConvertFrom-Json
    foreach ($entry in $started.PSObject.Properties) {
        if (Get-Process -Id $entry.Value -ErrorAction SilentlyContinue) {
            # /T takes the java or node child down with its window.
            & taskkill.exe /PID $entry.Value /T /F | Out-Null
            Write-Host "Stopped $($entry.Name)"
        } else {
            Write-Host "$($entry.Name) was already stopped"
        }
    }
    Remove-Item $pidFile
} else {
    Write-Host 'Nothing recorded as started by start-local.ps1.'
}

$ports = 8081, 8082, 8083, 4200
$leftover = Get-NetTCPConnection -State Listen -LocalPort $ports -ErrorAction SilentlyContinue
foreach ($connection in $leftover) {
    $process = Get-Process -Id $connection.OwningProcess -ErrorAction SilentlyContinue
    Write-Host ("Port {0} is still in use by {1} (PID {2}) - stop it with: Stop-Process -Id {2}" -f `
        $connection.LocalPort, $process.ProcessName, $connection.OwningProcess) -ForegroundColor Yellow
}
