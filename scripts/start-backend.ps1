param(
    [switch]$NoWait,
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$backendRoot = Join-Path $projectRoot 'backend'
$localEnv = Join-Path $projectRoot 'runtime-data\start-env.ps1'
$runtimeConfig = Join-Path $projectRoot 'runtime-data\system-settings.properties'
$logRoot = Join-Path $projectRoot 'runtime-logs'
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null

if (Test-Path -LiteralPath $localEnv) {
    . $localEnv
}

# 端口可用 SERVER_PORT 覆盖（默认 8080；本机若被其他程序占用可改为 8090）
$env:SERVER_PORT = if ($env:SERVER_PORT) { $env:SERVER_PORT } else { '8080' }
$backendPort = [int]$env:SERVER_PORT
$env:SPRING_PROFILES_ACTIVE = if ($env:SPRING_PROFILES_ACTIVE) { $env:SPRING_PROFILES_ACTIVE } else { 'local' }
$env:DB_URL = if ($env:DB_URL) { $env:DB_URL } else { 'jdbc:mysql://localhost:3306/cloud_gallery?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false' }
$env:DB_USERNAME = if ($env:DB_USERNAME) { $env:DB_USERNAME } else { 'root' }
$env:VECTOR_SERVICE_URL = if ($env:VECTOR_SERVICE_URL) { $env:VECTOR_SERVICE_URL } else { 'http://127.0.0.1:18001' }

# 独立管理控制台保存的连接参数优先于旧版 start-env.ps1，并在本次启动生效。
if (Test-Path -LiteralPath $runtimeConfig) {
    $settingMap = @{
        'database.url' = 'DB_URL'
        'database.username' = 'DB_USERNAME'
        'database.password' = 'DB_PASSWORD'
        'redis.host' = 'REDIS_HOST'
        'redis.port' = 'REDIS_PORT'
        'redis.database' = 'REDIS_DATABASE'
        'redis.password' = 'REDIS_PASSWORD'
        'vector.serviceUrl' = 'VECTOR_SERVICE_URL'
        'aliyun.apiKey' = 'ALIYUN_AI_API_KEY'
    }
    foreach ($line in Get-Content -LiteralPath $runtimeConfig -Encoding utf8) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) {
            continue
        }
        $separator = $line.IndexOf('=')
        if ($separator -lt 1) {
            continue
        }
        $key = $line.Substring(0, $separator).Trim()
        if (-not $settingMap.ContainsKey($key)) {
            continue
        }
        $value = $line.Substring($separator + 1).Trim()
        $value = $value.Replace('\=', '=').Replace('\:', ':').Replace('\\', '\')
        Set-Item -Path ("Env:" + $settingMap[$key]) -Value $value
    }
}

function Test-Port {
    param([int]$Port)
    return $null -ne (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
}

function Get-PortOwner {
    param([int]$Port)
    $connection = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $connection) {
        return $null
    }
    $process = Get-Process -Id $connection.OwningProcess -ErrorAction SilentlyContinue
    return if ($process) { "$($process.ProcessName)(PID=$($process.Id))" } else { "PID=$($connection.OwningProcess)" }
}

if (Test-Port -Port $backendPort) {
    $owner = Get-PortOwner -Port $backendPort
    if ($owner -like 'java*') {
        Write-Host "[backend] $backendPort 已有后端在运行（$owner），跳过重复启动。" -ForegroundColor Yellow
        exit 0
    }
    Write-Host "[backend] $backendPort 被 $owner 占用，等待其释放（最多 60 秒）..." -ForegroundColor Yellow
    $waitDeadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $waitDeadline -and (Test-Port -Port $backendPort)) {
        Start-Sleep -Seconds 3
    }
    if (Test-Port -Port $backendPort) {
        throw "$backendPort 仍被 $(Get-PortOwner -Port $backendPort) 占用，请释放后重试（或设置 SERVER_PORT 换端口）。"
    }
}

# 生成环境包装脚本（runtime-data 已被 Git 忽略），避免在命令行里转义 JDBC 连接串
$wrapper = Join-Path $projectRoot 'runtime-data\run-backend.ps1'
$envNames = @('SPRING_PROFILES_ACTIVE', 'SERVER_PORT', 'DB_URL', 'DB_USERNAME', 'DB_PASSWORD',
    'REDIS_HOST', 'REDIS_PORT', 'REDIS_DATABASE', 'REDIS_PASSWORD',
    'VECTOR_SERVICE_URL', 'ALIYUN_AI_API_KEY', 'GALLERY_PUBLIC_URL', 'GALLERY_PUBLIC_BASE_URL',
    'AGENT_EDIT_ENABLED', 'AGENT_SERVICE_URL', 'AGENT_SERVICE_SECRET')
$lines = @('# 由 start-backend.ps1 自动生成：设置环境变量并前台运行 Spring Boot（进程本身分离启动）')
foreach ($name in $envNames) {
    $value = [Environment]::GetEnvironmentVariable($name)
    if (-not [string]::IsNullOrEmpty($value)) {
        $escaped = $value.Replace("'", "''")
        $lines += "`$env:$name = '$escaped'"
    }
}
$lines += "Set-Location -LiteralPath '$backendRoot'"
$lines += "& mvn spring-boot:run"
Set-Content -LiteralPath $wrapper -Value $lines -Encoding utf8

# WMI 分离启动：不继承调用方句柄，脚本可立即退出
$pwshPath = (Get-Command pwsh.exe -ErrorAction SilentlyContinue).Source
if (-not $pwshPath) { $pwshPath = (Get-Command powershell.exe).Source }
$outLog = Join-Path $logRoot 'backend-run.out.log'
$errLog = Join-Path $logRoot 'backend-run.err.log'
$commandLine = 'cmd.exe /c ""{0}" -NoProfile -ExecutionPolicy Bypass -File "{1}" 1> "{2}" 2> "{3}""' -f `
    $pwshPath, $wrapper, $outLog, $errLog
$result = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
    CommandLine      = $commandLine
    CurrentDirectory = $backendRoot
}
if ($result.ReturnValue -ne 0) {
    throw "启动后端失败（WMI 返回 $($result.ReturnValue)）。"
}
Write-Host "[backend] 已启动（PID=$([int]$result.ProcessId)），端口 $backendPort，日志：runtime-logs\backend-run.*.log" -ForegroundColor Gray

if ($NoWait) {
    Write-Host "[backend] -NoWait：不等待就绪，请稍后轮询 http://127.0.0.1:$backendPort/api/agent-internal/health" -ForegroundColor Gray
    exit 0
}

# 轮询等待端口与健康检查
$startTime = Get-Date
$deadline = $startTime.AddSeconds($TimeoutSeconds)
$ready = $false
$lastReport = $startTime
while ((Get-Date) -lt $deadline) {
    if (Test-Port -Port $backendPort) {
        try {
            $resp = Invoke-WebRequest -Uri "http://127.0.0.1:$backendPort/api/agent-internal/health" -UseBasicParsing -TimeoutSec 3
            if ($resp.StatusCode -eq 200) { $ready = $true; break }
        } catch { }
    }
    if (((Get-Date) - $lastReport).TotalSeconds -ge 20) {
        Write-Host "[backend] 等待后端就绪中...（已等待 $([int]((Get-Date) - $startTime).TotalSeconds) 秒）" -ForegroundColor DarkGray
        $lastReport = Get-Date
    }
    Start-Sleep -Milliseconds 3000
}

if ($ready) {
    Write-Host "[backend] 云图库后端已就绪：http://127.0.0.1:$backendPort/api" -ForegroundColor Green
    exit 0
}
Write-Host '[backend] 后端未在超时时间内就绪，请查看 runtime-logs\backend-run.*.log。' -ForegroundColor Red
exit 1
