param(
    [string]$PidFile,
    [int[]]$Ports,
    [string]$Pattern
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$RunDir = Join-Path $Root ".musio\run"

if (-not $Ports -or $Ports.Count -eq 0) {
    $Ports = @(18765, 18766, 18767)
}

$ProcessPattern = if ($Pattern) { $Pattern } else { "app\.main|spring-boot:run|vite|npm run dev|mvn\.cmd" }

function Stop-ProcessTree {
    param([int]$ProcessId)

    $children = Get-CimInstance Win32_Process -Filter "ParentProcessId = $ProcessId" -ErrorAction SilentlyContinue
    foreach ($child in $children) {
        Stop-ProcessTree -ProcessId ([int]$child.ProcessId)
    }

    try {
        $process = Get-Process -Id $ProcessId -ErrorAction Stop
        Stop-Process -Id $ProcessId -Force -ErrorAction Stop
        Write-Host "Stopped pid=$ProcessId ($($process.ProcessName))"
    } catch {
        Write-Host "pid=$ProcessId is not running"
    }
}

if ($PidFile) {
    $pidFiles = @(Get-Item -Path $PidFile -ErrorAction SilentlyContinue)
} elseif (Test-Path $RunDir) {
    $pidFiles = @(Get-ChildItem -Path $RunDir -Filter "*.pid" -ErrorAction SilentlyContinue)
} else {
    $pidFiles = @()
}

foreach ($file in $pidFiles) {
    if ($null -ne $file) {
        $raw = Get-Content -Path $file.FullName -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($raw -match "^\d+$") {
            Stop-ProcessTree -ProcessId ([int]$raw)
        }
        Remove-Item -Path $file.FullName -Force -ErrorAction SilentlyContinue
    }
}

foreach ($port in $Ports) {
    $connections = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    foreach ($connection in $connections) {
        if ($connection.OwningProcess) {
            Stop-ProcessTree -ProcessId ([int]$connection.OwningProcess)
        }
    }
}

$escapedRoot = [regex]::Escape($Root)
$knownProcesses = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
    $_.CommandLine -and
    $_.CommandLine -match $escapedRoot -and
    $_.CommandLine -match $ProcessPattern
}

foreach ($process in $knownProcesses) {
    Stop-ProcessTree -ProcessId ([int]$process.ProcessId)
}

Write-Host "Musio local services stopped."
