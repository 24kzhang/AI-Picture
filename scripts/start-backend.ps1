$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$backendRoot = Join-Path $projectRoot 'backend'
$localEnv = Join-Path $projectRoot 'runtime-data\start-env.ps1'
$runtimeConfig = Join-Path $projectRoot 'runtime-data\system-settings.properties'

if (Test-Path -LiteralPath $localEnv) {
    . $localEnv
}

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

Push-Location $backendRoot
try {
    mvn spring-boot:run
} finally {
    Pop-Location
}
