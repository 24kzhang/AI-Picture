param(
    [switch]$StopInfra
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$agentRoot = Join-Path $projectRoot 'services\retouch-agent'
$composeFile = Join-Path $agentRoot 'docker\compose.yml'

# 结束 Agent API / Worker（按命令行匹配，避免误杀其他 python 进程）
$targets = @(Get-CimInstance Win32_Process -Filter "Name='python.exe'" |
    Where-Object { $_.CommandLine -like '*retouch-agent*' })
if ($targets.Count -eq 0) {
    Write-Host '[agent] 没有正在运行的 Agent 进程。' -ForegroundColor Yellow
} else {
    foreach ($proc in $targets) {
        Stop-Process -Id $proc.ProcessId -Force -ErrorAction SilentlyContinue
        Write-Host "[agent] 已结束进程 $($proc.ProcessId)" -ForegroundColor Gray
    }
}

if ($StopInfra) {
    Write-Host '[agent] 停止基础设施（PostgreSQL / Redis / RustFS）...' -ForegroundColor Cyan
    docker compose -f $composeFile down
}

Write-Host '[agent] 完成。' -ForegroundColor Green
