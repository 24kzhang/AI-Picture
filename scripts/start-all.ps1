param(
    [switch]$NoBrowser,
    [switch]$NoAgent
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$logRoot = Join-Path $projectRoot 'runtime-logs'
$dataRoot = Join-Path $projectRoot 'runtime-data'
$pwshPath = (Get-Command pwsh -ErrorAction Stop).Source

New-Item -ItemType Directory -Force -Path $logRoot, $dataRoot | Out-Null

function Test-Port {
    param([int]$Port)
    $connection = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    return $null -ne $connection
}

function Start-GalleryProcess {
    param(
        [string]$Name,
        [int]$Port,
        [string]$ScriptPath
    )
    if (Test-Port -Port $Port) {
        Write-Host "$Name 已在端口 $Port 运行，跳过重复启动。" -ForegroundColor Yellow
        return $null
    }
    $outLog = Join-Path $logRoot ($Name + '.out.log')
    $errorLog = Join-Path $logRoot ($Name + '.err.log')
    $startParams = @{
        FilePath = $pwshPath
        ArgumentList = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $ScriptPath)
        WindowStyle = 'Hidden'
        RedirectStandardOutput = $outLog
        RedirectStandardError = $errorLog
        PassThru = $true
    }
    $process = Start-Process @startParams
    Write-Host "$Name 已启动，PID=$($process.Id)，日志：$outLog" -ForegroundColor Green
    return [ordered]@{
        name = $Name
        pid = $process.Id
        port = $Port
        startedAt = (Get-Date).ToString('s')
    }
}

$processes = @()
$vector = Start-GalleryProcess -Name 'vector' -Port 18001 -ScriptPath (Join-Path $PSScriptRoot 'start-vector.ps1')
if ($vector) { $processes += $vector }

$backend = Start-GalleryProcess -Name 'backend' -Port 8080 -ScriptPath (Join-Path $PSScriptRoot 'start-backend.ps1')
if ($backend) { $processes += $backend }

$frontend = Start-GalleryProcess -Name 'frontend' -Port 5173 -ScriptPath (Join-Path $PSScriptRoot 'start-frontend.ps1')
if ($frontend) { $processes += $frontend }

# Agent 修图服务：失败仅告警，不阻断云图库主体（可 -NoAgent 跳过）
if (-not $NoAgent) {
    try {
        & (Join-Path $PSScriptRoot 'start-agent.ps1')
    } catch {
        Write-Host "修图 Agent 启动失败（云图库主体不受影响）：$($_.Exception.Message)" -ForegroundColor Yellow
    }
}

if ($processes.Count -gt 0) {
    $processes | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $dataRoot 'processes.json') -Encoding utf8
}

$requiredPorts = @(18001, 8080, 5173)
$deadline = (Get-Date).AddMinutes(2)
while ((Get-Date) -lt $deadline) {
    $missingPorts = @($requiredPorts | Where-Object { -not (Test-Port -Port $_) })
    if ($missingPorts.Count -eq 0) {
        break
    }
    Start-Sleep -Seconds 2
}

$missingPorts = @($requiredPorts | Where-Object { -not (Test-Port -Port $_) })
if ($missingPorts.Count -eq 0) {
    Write-Host '向量服务、后端和前端均已就绪：http://localhost:5173' -ForegroundColor Cyan
    if (-not $NoBrowser) {
        Start-Process 'http://localhost:5173'
    }
} else {
    Write-Host "以下端口在 2 分钟内未就绪：$($missingPorts -join ', ')，请检查 runtime-logs。" -ForegroundColor Red
    exit 1
}
