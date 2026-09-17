param(
    [switch]$SkipInfra,
    [switch]$SkipMigrate,
    [switch]$NoWait
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

function Get-AgentProcesses {
    param([string]$Pattern)
    return @(Get-CimInstance Win32_Process -Filter "Name='python.exe'" |
        Where-Object { $_.CommandLine -like '*retouch-agent*' -and $_.CommandLine -like "*$Pattern*" })
}

function Start-DetachedProcess {
    <#
      通过 WMI 创建进程并用 cmd 重定向日志。
      相比 Start-Process，子进程不继承调用方的标准句柄，
      因此调用脚本可以立即退出，不会再挂住终端。
    #>
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [string]$WorkingDirectory,
        [string]$OutLog,
        [string]$ErrLog
    )
    $quoted = ($Arguments | ForEach-Object {
            if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ }
        }) -join ' '
    $commandLine = 'cmd.exe /c ""{0}" {1} 1> "{2}" 2> "{3}""' -f $FilePath, $quoted, $OutLog, $ErrLog
    $result = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
        CommandLine      = $commandLine
        CurrentDirectory = $WorkingDirectory
    }
    if ($result.ReturnValue -ne 0) {
        throw "启动进程失败（WMI 返回 $($result.ReturnValue)）：$FilePath"
    }
    return [int]$result.ProcessId
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

# 4. 启动 API 与 Worker（分离式，日志写入 runtime-logs）
$started = @()
$apiProcs = Get-AgentProcesses -Pattern 'uvicorn'
if (Test-Port -Port 7302 -or $apiProcs.Count -gt 0) {
    Write-Host "[agent] API 已在运行（进程 $($apiProcs.Count) 个），跳过重复启动。" -ForegroundColor Yellow
    if ($apiProcs.Count -gt 1) {
        Write-Host '[agent] 检测到重复 API 进程，建议先执行 scripts\stop-agent.ps1 清理。' -ForegroundColor Yellow
    }
} else {
    $apiPid = Start-DetachedProcess -FilePath $venvPython `
        -Arguments @('-m', 'uvicorn', 'app.main:app', '--host', '127.0.0.1', '--port', '7302') `
        -WorkingDirectory $agentBackend `
        -OutLog (Join-Path $logRoot 'agent-api.out.log') `
        -ErrLog (Join-Path $logRoot 'agent-api.err.log')
    $started += "agent-api(PID=$apiPid)"
}

$workerProcs = Get-AgentProcesses -Pattern 'app.worker'
if ($workerProcs.Count -gt 0) {
    Write-Host "[agent] Worker 已在运行（进程 $($workerProcs.Count) 个），跳过重复启动。" -ForegroundColor Yellow
    if ($workerProcs.Count -gt 1) {
        Write-Host '[agent] 检测到重复 Worker 进程，建议先执行 scripts\stop-agent.ps1 清理。' -ForegroundColor Yellow
    }
} else {
    $workerPid = Start-DetachedProcess -FilePath $venvPython `
        -Arguments @('-m', 'arq', 'app.worker.WorkerSettings') `
        -WorkingDirectory $agentBackend `
        -OutLog (Join-Path $logRoot 'agent-worker.out.log') `
        -ErrLog (Join-Path $logRoot 'agent-worker.err.log')
    $started += "agent-worker(PID=$workerPid)"
}

if ($started.Count -gt 0) {
    Write-Host "[agent] 已启动：$($started -join '，')；日志位于 runtime-logs。" -ForegroundColor Gray
}

if ($NoWait) {
    Write-Host '[agent] -NoWait：不等待健康检查，请稍后自行轮询 /api/health。' -ForegroundColor Gray
    exit 0
}

# 5. 轮询等待 API 就绪（每 3 秒一次，最长 90 秒）
$startTime = Get-Date
$deadline = $startTime.AddSeconds(90)
$healthy = $false
$lastReport = $startTime
while ((Get-Date) -lt $deadline) {
    if (Test-Port -Port 7302) {
        try {
            $resp = Invoke-WebRequest -Uri 'http://127.0.0.1:7302/api/health' -UseBasicParsing -TimeoutSec 3
            if ($resp.StatusCode -eq 200) { $healthy = $true; break }
        } catch { }
    }
    if (((Get-Date) - $lastReport).TotalSeconds -ge 15) {
        Write-Host "[agent] 等待 API 就绪中...（已等待 $([int]((Get-Date) - $startTime).TotalSeconds) 秒）" -ForegroundColor DarkGray
        $lastReport = Get-Date
    }
    Start-Sleep -Milliseconds 3000
}

if ($healthy) {
    Write-Host '[agent] 修图 Agent 已就绪：http://127.0.0.1:7302/api/health' -ForegroundColor Green
    exit 0
}
Write-Host '[agent] API 健康检查未在 90 秒内通过，请查看 runtime-logs\agent-api.*.log。' -ForegroundColor Red
exit 1
