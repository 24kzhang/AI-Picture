$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$serviceRoot = Join-Path $projectRoot 'vector-service'
$localConfig = Join-Path $projectRoot 'backend\src\main\resources\application-local.yml'
$runtimeConfig = Join-Path $projectRoot 'runtime-data\system-settings.properties'

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

Push-Location $serviceRoot
try {
    conda run --no-capture-output -n cloud-gallery-vector `
        python -m uvicorn app.main:app --host 127.0.0.1 --port $vectorPort
} finally {
    Pop-Location
}
