$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$frontendRoot = Join-Path $projectRoot 'frontend'

Push-Location $frontendRoot
try {
    npm run dev -- --host 127.0.0.1 --port 5173 --strictPort
} finally {
    Pop-Location
}
