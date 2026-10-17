$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$serviceRoot = Join-Path $projectRoot 'vector-service'
$env:EMBEDDING_BASE_URL = if ($env:EMBEDDING_BASE_URL) { $env:EMBEDDING_BASE_URL } else { 'http://172.18.20.44:30066/embedding' }
$env:EMBEDDING_MODEL = if ($env:EMBEDDING_MODEL) { $env:EMBEDDING_MODEL } else { 'qwen3-vl-embedding-2b-finetuned' }
$env:EMBEDDING_DIMENSION = if ($env:EMBEDDING_DIMENSION) { $env:EMBEDDING_DIMENSION } else { '2048' }
$env:EMBEDDING_REVISION = if ($env:EMBEDDING_REVISION) { $env:EMBEDDING_REVISION } else { 'checkpoint-60' }
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
