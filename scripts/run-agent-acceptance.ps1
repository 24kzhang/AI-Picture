param(
    [switch]$SkipApi,
    [switch]$SkipBrowser
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$node = 'C:\nvm4w\nodejs\node.exe'
if (-not (Test-Path -LiteralPath $node)) {
    $node = (Get-Command node -ErrorAction Stop).Source
}

if (-not $SkipApi) {
    & $node (Join-Path $projectRoot 'scripts\acceptance\agent-api-smoke.mjs')
}

if (-not $SkipBrowser) {
    & $node (Join-Path $projectRoot 'scripts\acceptance\agent-browser.mjs')
}
