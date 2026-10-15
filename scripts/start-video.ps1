param([switch]$Install)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$localEnv = Join-Path $projectRoot 'runtime-data\start-env.ps1'
if (Test-Path -LiteralPath $localEnv) { . $localEnv }
$backendPort = if ($env:SERVER_PORT) { $env:SERVER_PORT } else { '8080' }
if (-not $env:VIDEO_JAVA_URL) { $env:VIDEO_JAVA_URL = "http://127.0.0.1:$backendPort/api" }
$env:NODE_OPTIONS = '--use-system-ca'
$env:npm_config_cache = Join-Path $projectRoot 'runtime-data\npm-cache'
$env:npm_config_registry = 'https://registry.npmmirror.com'
if (-not $env:VIDEO_FFMPEG -and $IsWindows) {
    $mediaTools = Join-Path $projectRoot 'runtime-data\media-tools'
    $ffmpegFile = Join-Path $mediaTools 'ffmpeg.exe'
    if (-not (Test-Path -LiteralPath $ffmpegFile)) {
        $available = Get-Command ffmpeg -ErrorAction SilentlyContinue
        if ($available) { $env:VIDEO_FFMPEG = $available.Source }
        else {
            $condaCommand = Get-Command conda -ErrorAction SilentlyContinue
            if (-not $condaCommand -and (Test-Path 'E:\anaconda\Scripts\conda.exe')) { $condaCommand = Get-Item 'E:\anaconda\Scripts\conda.exe' }
            if (-not $condaCommand) { throw '请安装 FFmpeg 6+ 并设置 VIDEO_FFMPEG，或提供 codex conda 环境' }
            $condaExe = if ($condaCommand.Source) { $condaCommand.Source } else { $condaCommand.FullName }
            $binary = & $condaExe run --no-capture-output -n codex python -c 'import imageio_ffmpeg; print(imageio_ffmpeg.get_ffmpeg_exe())'
            if ($LASTEXITCODE) {
                & $condaExe run --no-capture-output -n codex python -m pip install imageio-ffmpeg==0.6.0 -i https://pypi.tuna.tsinghua.edu.cn/simple
                if ($LASTEXITCODE) { throw 'FFmpeg 安装失败' }
                $binary = & $condaExe run --no-capture-output -n codex python -c 'import imageio_ffmpeg; print(imageio_ffmpeg.get_ffmpeg_exe())'
            }
            New-Item -ItemType Directory -Path $mediaTools -Force | Out-Null
            Copy-Item -LiteralPath ($binary | Select-Object -Last 1).Trim() -Destination $ffmpegFile
        }
    }
    if (-not $env:VIDEO_FFMPEG) { $env:VIDEO_FFMPEG = $ffmpegFile }
}
Push-Location (Join-Path $projectRoot 'services\video-agent')
try {
    if ($Install -or -not (Test-Path 'node_modules')) { npm ci --registry=https://registry.npmmirror.com; if ($LASTEXITCODE) { throw '视频服务依赖安装失败' } }
    npm start
} finally { Pop-Location }
