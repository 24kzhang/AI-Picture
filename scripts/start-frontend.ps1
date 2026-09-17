param(
    [switch]$NoWait,
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$frontendRoot = Join-Path $projectRoot 'frontend'
$logRoot = Join-Path $projectRoot 'runtime-logs'
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null

function Test-Port {
    param([int]$Port)
    return $null -ne (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
}

if (Test-Port -Port 5173) {
    Write-Host '[frontend] 5173 已有服务在运行，跳过重复启动。' -ForegroundColor Yellow
    exit 0
}

# WMI 分离启动：不继承调用方句柄，脚本可立即退出
$pwshPath = (Get-Command pwsh.exe -ErrorAction SilentlyContinue).Source
if (-not $pwshPath) { $pwshPath = (Get-Command powershell.exe).Source }
$outLog = Join-Path $logRoot 'frontend-run.out.log'
$errLog = Join-Path $logRoot 'frontend-run.err.log'
$npmCmd = 'npm run dev -- --host 127.0.0.1 --port 5173 --strictPort'
$commandLine = 'cmd.exe /c ""{0}" -NoProfile -ExecutionPolicy Bypass -Command "Set-Location ''{1}''; {2}" 1> "{3}" 2> "{4}""' -f `
    $pwshPath, $frontendRoot, $npmCmd, $outLog, $errLog
$result = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
    CommandLine      = $commandLine
    CurrentDirectory = $frontendRoot
}
if ($result.ReturnValue -ne 0) {
    throw "启动前端失败（WMI 返回 $($result.ReturnValue)）。"
}
Write-Host "[frontend] 已启动（PID=$([int]$result.ProcessId)），日志：runtime-logs\frontend-run.*.log" -ForegroundColor Gray

if ($NoWait) {
    Write-Host '[frontend] -NoWait：不等待就绪，请稍后访问 http://127.0.0.1:5173' -ForegroundColor Gray
    exit 0
}

$startTime = Get-Date
$deadline = $startTime.AddSeconds($TimeoutSeconds)
$ready = $false
$lastReport = $startTime
while ((Get-Date) -lt $deadline) {
    if (Test-Port -Port 5173) {
        try {
            $resp = Invoke-WebRequest -Uri 'http://127.0.0.1:5173' -UseBasicParsing -TimeoutSec 3
            if ($resp.StatusCode -eq 200) { $ready = $true; break }
        } catch { }
    }
    if (((Get-Date) - $lastReport).TotalSeconds -ge 20) {
        Write-Host "[frontend] 等待前端就绪中...（已等待 $([int]((Get-Date) - $startTime).TotalSeconds) 秒）" -ForegroundColor DarkGray
        $lastReport = Get-Date
    }
    Start-Sleep -Milliseconds 2000
}

if ($ready) {
    Write-Host '[frontend] 前端已就绪：http://127.0.0.1:5173' -ForegroundColor Green
    exit 0
}
Write-Host '[frontend] 前端未在超时时间内就绪，请查看 runtime-logs\frontend-run.*.log。' -ForegroundColor Red
exit 1
