@echo off
setlocal EnableDelayedExpansion
title AI 修图智能体 - 控制台
cd /d "%~dp0"
REM 所有 Python 子进程统一继承 UTF-8 模式（中文 Windows 默认 GBK 会导致各类编码问题）
set PYTHONUTF8=1
if not exist logs mkdir logs

echo ==========================================================
echo            AI 修图智能体 (ai-retouch-agent)
echo ==========================================================
echo.

REM ---------- 1. 检查服务是否已在运行 ----------
curl -s -o nul http://127.0.0.1:7302/api/health 2>nul
if not errorlevel 1 (
    echo 检测到服务已在运行，直接进入控制台。
    ping -n 2 127.0.0.1 >nul
    goto menu
)

REM ---------- 2. 启动 Docker 引擎 ----------
docker info >nul 2>&1
if errorlevel 1 (
    echo [1/6] 正在启动 Docker Desktop，通常需要 1-2 分钟...
    if not exist "C:\Program Files\Docker\Docker\Docker Desktop.exe" (
        echo 错误：未找到 Docker Desktop，请先安装。
        goto fail
    )
    start "" "C:\Program Files\Docker\Docker\Docker Desktop.exe"
    set /a tries=0
:wait_docker
    ping -n 6 127.0.0.1 >nul
    docker info >nul 2>&1
    if errorlevel 1 (
        set /a tries+=1
        echo       ...等待 Docker 引擎就绪，已等 !tries! x 5 秒
        if !tries! geq 36 (
            echo 错误：Docker 引擎等待超时。请手动打开 Docker Desktop 确认可启动。
            goto fail
        )
        goto wait_docker
    )
) else (
    echo [1/6] Docker 引擎已在运行。
)

REM ---------- 3. 启动基础设施容器 ----------
echo [2/6] 启动 PostgreSQL / Redis / RustFS 容器...
docker compose up -d --wait
if errorlevel 1 (
    echo 错误：容器启动失败，请检查上方错误信息。
    goto fail
)
echo       基础设施容器已全部就绪。

REM ---------- 4. 数据库迁移 ----------
echo [3/6] 执行数据库迁移...
pushd backend
".venv\Scripts\python.exe" migrate.py
if errorlevel 1 (
    popd
    echo 错误：数据库迁移失败。
    goto fail
)
popd
echo       数据库结构已是最新。

REM ---------- 5. 后台启动三个服务（无窗口，日志写入 logs 目录） ----------
echo [4/6] 启动后端 API（日志: logs\backend.log）...
start "" /b cmd /c "cd backend && .venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 7302 >..\logs\backend.log 2>&1"

echo [5/6] 启动异步 Worker（日志: logs\worker.log）...
start "" /b cmd /c "cd backend && .venv\Scripts\python.exe -m arq app.worker.WorkerSettings >..\logs\worker.log 2>&1"

echo [6/6] 启动前端开发服务器（日志: logs\frontend.log）...
start "" /b cmd /c "cd frontend && npm run dev >..\logs\frontend.log 2>&1"

REM ---------- 6. 等待后端就绪 ----------
echo.
echo 等待后端服务就绪...
set /a tries=0
:wait_api
ping -n 3 127.0.0.1 >nul
curl -s -o nul http://127.0.0.1:7302/api/health 2>nul
if errorlevel 1 (
    set /a tries+=1
    if !tries! geq 30 (
        echo 警告：后端未就绪，可稍后在菜单按 R 刷新状态，或按 1 查看日志排查。
        ping -n 3 127.0.0.1 >nul
    ) else (
        goto wait_api
    )
)

:menu
cls
echo ==========================================================
echo                 AI 修图智能体 - 已启动
echo ==========================================================
echo.
echo   前端应用    http://127.0.0.1:7301
echo   API 文档    http://127.0.0.1:7302/api/docs
echo   健康检查    http://127.0.0.1:7302/api/health
echo.
echo   PostgreSQL  localhost:7311   账号: retouch  密码: retouch_dev
echo   Redis       localhost:7312
echo   对象存储    localhost:7313   (RustFS S3 API)
echo.
echo ----------------------------------------------------------
echo   三个服务在本窗口后台运行，日志写入 logs\ 目录
echo.
echo   [1] 查看后端日志    [2] 查看 Worker 日志    [3] 查看前端日志
echo   [B] 打开浏览器      [R] 刷新健康状态
echo   [Q] 停止所有服务并退出（直接关闭本窗口同样会停止服务）
echo ==========================================================
echo.
choice /c 123BRQ /n /m "  请选择: "
if errorlevel 6 goto stop_all
if errorlevel 5 goto refresh
if errorlevel 4 goto open_browser
if errorlevel 3 goto log_frontend
if errorlevel 2 goto log_worker
goto log_backend

:open_browser
start "" http://127.0.0.1:7301
goto menu

:refresh
echo.
curl -s http://127.0.0.1:7302/api/health
echo.
pause
goto menu

:log_backend
cls
echo ---------- logs\backend.log（最后 60 行） ----------
powershell -NoProfile -Command "Get-Content 'logs\backend.log' -Tail 60 -Encoding UTF8" 2>nul
echo.
pause
goto menu

:log_worker
cls
echo ---------- logs\worker.log（最后 60 行） ----------
powershell -NoProfile -Command "Get-Content 'logs\worker.log' -Tail 60 -Encoding UTF8" 2>nul
echo.
pause
goto menu

:log_frontend
cls
echo ---------- logs\frontend.log（最后 60 行） ----------
powershell -NoProfile -Command "Get-Content 'logs\frontend.log' -Tail 60 -Encoding UTF8" 2>nul
echo.
pause
goto menu

:stop_all
echo.
echo 正在停止所有服务...
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='python.exe' OR Name='node.exe'\" | Where-Object { ($_.ExecutablePath -like '*ai-retouch-agent*') -or ($_.CommandLine -like '*ai-retouch-agent*') } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"
echo 服务已全部停止（Docker 容器保留，下次启动更快）。
ping -n 3 127.0.0.1 >nul
exit /b 0

:fail
echo.
echo 启动失败，请把上方错误信息截图发给我。
pause
exit /b 1
