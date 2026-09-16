param(
    [switch]$SkipInfra,
    [switch]$SkipMigrate
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$agentRoot = Join-Path $projectRoot 'services\retouch-agent'
$agentBackend = Join-Path $agentRoot 'backend'
$logRoot = Join-Path $projectRoot 'runtime-logs'
$composeFile = Join-Path $agentRoot 'docker\compose.yml'

New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
$env:PYTHONUTF8 = '1'

function Test-Port {
    param([int]$Port)
    $connection = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    return $null -ne $connection
}

function Test-AgentWorker {
    $procs = Get-CimInstance Win32_Process -Filter "Name='python.exe'" |
        Where-Object { $_.CommandLine -like '*retouch-agent*' -and $_.CommandLine -like '*app.worker*' }
    return ($null -ne $procs -and @($procs).Count -gt 0)
}

# 1. 基础设施（PostgreSQL / Redis / RustFS）
if (-not $SkipInfra) {
    Write-Host '[agent] 启动基础设施（PostgreSQL / Redis / RustFS）...' -ForegroundColor Cyan
    docker compose -f $composeFile up -d --wait
    if ($LASTEXITCODE -ne 0) {
        throw 'Agent 基础设施启动失败，请确认 Docker Desktop 已运行。'
    }
}

# 2. Python 虚拟环境
$venvPython = Join-Path $agentBackend '.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $venvPython)) {
    Write-Host '[agent] 初始化 Python 虚拟环境（uv sync）...' -ForegroundColor Cyan
    Push-Location $agentBackend
    try {
        uv sync --frozen
        if ($LASTEXITCODE -ne 0) { throw 'uv sync 失败。' }
    } finally {
        Pop-Location
    }
}

# 3. 数据库迁移
if (-not $SkipMigrate) {
    Write-Host '[agent] 执行数据库迁移...' -ForegroundColor Cyan
    Push-Location $agentBackend
    try {
        & $venvPython migrate.py
        if ($LASTEXITCODE -ne 0) { throw 'Agent 数据库迁移失败。' }
    } finally {
        Pop-Location
    }
}

# 4. 启动 API 与 Worker
$started = @()
if (Test-Port -Port 7302) {
    Write-Host '[agent] API 已在端口 7302 运行，跳过重复启动。' -ForegroundColor Yellow
} else {
    $apiOut = Join-Path $logRoot 'agent-api.out.log'
    $apiErr = Join-Path $logRoot 'agent-api.err.log'
    $proc = Start-Process -FilePath $venvPython `
        -ArgumentList @('-m', 'uvicorn', 'app.main:app', '--host', '127.0.0.1', '--port', '7302') `
        -WorkingDirectory $agentBackend `
        -WindowStyle Hidden `
        -RedirectStandardOutput $apiOut -RedirectStandardError $apiErr -PassThru
    $started += "agent-api(PID=$($proc.Id))"
}

if (Test-AgentWorker) {
    Write-Host '[agent] Worker 已在运行，跳过重复启动。' -ForegroundColor Yellow
} else {
    $workerOut = Join-Path $logRoot 'agent-worker.out.log'
    $workerErr = Join-Path $logRoot 'agent-worker.err.log'
    $workerProc = Start-Process -FilePath $venvPython `
        -ArgumentList @('-m', 'arq', 'app.worker.WorkerSettings') `
        -WorkingDirectory $agentBackend `
        -WindowStyle Hidden `
        -RedirectStandardOutput $workerOut -RedirectStandardError $workerErr -PassThru
    $started += "agent-worker(PID=$($workerProc.Id))"
}

# 5. 等待 API 就绪
$deadline = (Get-Date).AddSeconds(90)
$healthy = $false
while ((Get-Date) -lt $deadline) {
    if (Test-Port -Port 7302) {
        try {
            $resp = Invoke-WebRequest -Uri 'http://127.0.0.1:7302/api/health' -UseBasicParsing -TimeoutSec 3
            if ($resp.StatusCode -eq 200) { $healthy = $true; break }
        } catch { }
    }
    Start-Sleep -Seconds 2
}

if ($healthy) {
    Write-Host '[agent] 修图 Agent 已就绪：http://127.0.0.1:7302/api/health' -ForegroundColor Green
    if ($started.Count -gt 0) {
        Write-Host "[agent] 本次启动：$($started -join '，')；日志位于 runtime-logs。" -ForegroundColor Gray
    }
} else {
    Write-Host '[agent] API 健康检查未在 90 秒内通过，请查看 runtime-logs\agent-api.*.log。' -ForegroundColor Red
    exit 1
}
exit 0
