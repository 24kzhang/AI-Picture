param(
    [switch]$NoWait,
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$serviceRoot = Join-Path $projectRoot 'vector-service'
$localConfig = Join-Path $projectRoot 'backend\src\main\resources\application-local.yml'
$runtimeConfig = Join-Path $projectRoot 'runtime-data\system-settings.properties'
$logRoot = Join-Path $projectRoot 'runtime-logs'
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null

if (-not $env:DASHSCOPE_API_KEY -and (Test-Path -LiteralPath $runtimeConfig)) {
    $runtimeKeyLine = Select-String -LiteralPath $runtimeConfig -Pattern '^aliyun\.apiKey=(.*)$' | Select-Object -First 1
    if ($runtimeKeyLine) {
        $env:DASHSCOPE_API_KEY = $runtimeKeyLine.Matches[0].Groups[1].Value.Trim()
    }
}

if (-not $env:DASHSCOPE_API_KEY -and (Test-Path -LiteralPath $localConfig)) {
    $apiKeyLine = Select-String -LiteralPath $localConfig `
        -Pattern '^\s*apiKey\s*:\s*(.+)$' | Select-Object -First 1
    if ($apiKeyLine) {
        $env:DASHSCOPE_API_KEY = $apiKeyLine.Matches[0].Groups[1].Value.Trim().Trim('"').Trim("'")
    }
}

if (-not $env:DASHSCOPE_API_KEY) {
    throw '请在 application-local.yml 中配置 aliYunAi.apiKey，或设置 DASHSCOPE_API_KEY 环境变量。'
}

$env:EMBEDDING_MODEL = if ($env:EMBEDDING_MODEL) { $env:EMBEDDING_MODEL } else { 'tongyi-embedding-vision-flash-2026-03-06' }
$env:EMBEDDING_DIMENSION = if ($env:EMBEDDING_DIMENSION) { $env:EMBEDDING_DIMENSION } else { '512' }
$env:EMBEDDING_RES_LEVEL = if ($env:EMBEDDING_RES_LEVEL) { $env:EMBEDDING_RES_LEVEL } else { '1' }
$vectorPort = if ($env:VECTOR_PORT) { $env:VECTOR_PORT } else { '18001' }
if (-not $env:CHROMA_PATH -and $env:LOCALAPPDATA) {
    $env:CHROMA_PATH = Join-Path $env:LOCALAPPDATA 'CloudGallery\chroma'
}

function Test-Port {
    param([int]$Port)
    return $null -ne (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
}

if (Test-Port -Port ([int]$vectorPort)) {
    Write-Host "[vector] $vectorPort 已有服务在运行，跳过重复启动。" -ForegroundColor Yellow
    exit 0
}

# 生成环境包装脚本（runtime-data 已被 Git 忽略），分离式启动，避免终端被长驻进程挂住
$wrapper = Join-Path $projectRoot 'runtime-data\run-vector.ps1'
$lines = @('# 由 start-vector.ps1 自动生成：设置环境变量并前台运行向量服务（进程本身分离启动）')
foreach ($name in @('DASHSCOPE_API_KEY', 'EMBEDDING_MODEL', 'EMBEDDING_DIMENSION', 'EMBEDDING_RES_LEVEL', 'CHROMA_PATH')) {
    $value = [Environment]::GetEnvironmentVariable($name)
    if (-not [string]::IsNullOrEmpty($value)) {
        $lines += "`$env:$name = '$($value.Replace("'", "''"))'"
    }
}
$lines += "Set-Location -LiteralPath '$serviceRoot'"
$lines += "conda run --no-capture-output -n cloud-gallery-vector python -m uvicorn app.main:app --host 127.0.0.1 --port $vectorPort"
Set-Content -LiteralPath $wrapper -Value $lines -Encoding utf8

$pwshPath = (Get-Command pwsh.exe -ErrorAction SilentlyContinue).Source
if (-not $pwshPath) { $pwshPath = (Get-Command powershell.exe).Source }
$outLog = Join-Path $logRoot 'vector-run.out.log'
$errLog = Join-Path $logRoot 'vector-run.err.log'
$commandLine = 'cmd.exe /c ""{0}" -NoProfile -ExecutionPolicy Bypass -File "{1}" 1> "{2}" 2> "{3}""' -f `
    $pwshPath, $wrapper, $outLog, $errLog
$result = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
    CommandLine      = $commandLine
    CurrentDirectory = $serviceRoot
}
if ($result.ReturnValue -ne 0) {
    throw "启动向量服务失败（WMI 返回 $($result.ReturnValue)）。"
}
Write-Host "[vector] 已启动（PID=$([int]$result.ProcessId)），日志：runtime-logs\vector-run.*.log" -ForegroundColor Gray

if ($NoWait) {
    Write-Host '[vector] -NoWait：不等待就绪，请稍后轮询 http://127.0.0.1:18001/health' -ForegroundColor Gray
    exit 0
}

$startTime = Get-Date
$deadline = $startTime.AddSeconds($TimeoutSeconds)
$ready = $false
$lastReport = $startTime
while ((Get-Date) -lt $deadline) {
    if (Test-Port -Port ([int]$vectorPort)) {
        try {
            $resp = Invoke-WebRequest -Uri "http://127.0.0.1:$vectorPort/health" -UseBasicParsing -TimeoutSec 3
            if ($resp.StatusCode -eq 200) { $ready = $true; break }
        } catch { }
    }
    if (((Get-Date) - $lastReport).TotalSeconds -ge 20) {
        Write-Host "[vector] 等待向量服务就绪中...（已等待 $([int]((Get-Date) - $startTime).TotalSeconds) 秒）" -ForegroundColor DarkGray
        $lastReport = Get-Date
    }
    Start-Sleep -Milliseconds 3000
}

if ($ready) {
    Write-Host "[vector] 向量服务已就绪：http://127.0.0.1:$vectorPort/health" -ForegroundColor Green
    exit 0
}
Write-Host '[vector] 向量服务未在超时时间内就绪，请查看 runtime-logs\vector-run.*.log。' -ForegroundColor Red
exit 1
