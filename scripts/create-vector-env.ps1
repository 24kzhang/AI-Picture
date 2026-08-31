$ErrorActionPreference = 'Stop'

$envName = 'cloud-gallery-vector'
$projectRoot = Split-Path -Parent $PSScriptRoot
$requirements = Join-Path $projectRoot 'vector-service\requirements.txt'

$envList = conda env list --json | ConvertFrom-Json
$exists = $envList.envs | Where-Object { (Split-Path $_ -Leaf) -eq $envName }
if (-not $exists) {
    conda create -n $envName python=3.11 pip -y
}

conda run -n $envName python -m pip install --upgrade pip `
    -i https://pypi.tuna.tsinghua.edu.cn/simple
conda run -n $envName python -m pip install -r $requirements `
    -i https://pypi.tuna.tsinghua.edu.cn/simple `
    --extra-index-url https://pypi.org/simple

Write-Host "向量环境 $envName 已准备完成（百炼 API + Chroma，无需 Docker）。" -ForegroundColor Green
