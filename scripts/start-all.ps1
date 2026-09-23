param(
    [switch]$NoBrowser
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$logRoot = Join-Path $projectRoot 'runtime-logs'
$dataRoot = Join-Path $projectRoot 'runtime-data'
$pwshPath = (Get-Command pwsh -ErrorAction Stop).Source
$localEnv = Join-Path $dataRoot 'start-env.ps1'

# 读取本地端口配置，避免启动检查与 Spring Boot 实际端口不一致。
if (Test-Path -LiteralPath $localEnv) {
    . $localEnv
}
$backendPort = if ($env:SERVER_PORT) { [int]$env:SERVER_PORT } else { 8080 }
$frontendPort = 5173
$vectorPort = if ($env:VECTOR_PORT) { [int]$env:VECTOR_PORT } else { 18001 }

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
$vector = Start-GalleryProcess -Name 'vector' -Port $vectorPort -ScriptPath (Join-Path $PSScriptRoot 'start-vector.ps1')
if ($vector) { $processes += $vector }

$backend = Start-GalleryProcess -Name 'backend' -Port $backendPort -ScriptPath (Join-Path $PSScriptRoot 'start-backend.ps1')
if ($backend) { $processes += $backend }

$frontend = Start-GalleryProcess -Name 'frontend' -Port $frontendPort -ScriptPath (Join-Path $PSScriptRoot 'start-frontend.ps1')
if ($frontend) { $processes += $frontend }

if ($processes.Count -gt 0) {
    $processes | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $dataRoot 'processes.json') -Encoding utf8
}

$requiredPorts = @($vectorPort, $backendPort, $frontendPort)
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
    Write-Host ''
    Write-Host '项目已启动，服务地址如下：' -ForegroundColor Cyan
    Write-Host "[前端首页]     http://localhost:$frontendPort/"
    Write-Host "[管理页面]     http://localhost:$backendPort/api/manage/"
    Write-Host "[后端 API]     http://localhost:$backendPort/api"
    Write-Host "[接口文档]     http://localhost:$backendPort/api/doc.html"
    Write-Host "[向量服务]     http://localhost:$vectorPort/"
    Write-Host "[向量健康检查] http://localhost:$vectorPort/health"
    Write-Host ''
    Write-Host "日志目录：$logRoot" -ForegroundColor DarkGray
    Write-Host '关闭本窗口不会自动停止已启动的服务；请使用任务管理器或停止脚本结束服务。' -ForegroundColor Yellow
    if (-not $NoBrowser) {
        Start-Process "http://localhost:$frontendPort"
    }
} else {
    Write-Host "以下端口在 2 分钟内未就绪：$($missingPorts -join ', ')，请检查 runtime-logs。" -ForegroundColor Red
    exit 1
}
