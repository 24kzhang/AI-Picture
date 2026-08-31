@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"
where.exe pwsh.exe >nul 2>nul
if errorlevel 1 (
  echo [错误] 未找到 PowerShell 7（pwsh），请先安装后重试。
  pause
  exit /b 1
)
pwsh.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-all.ps1"
if errorlevel 1 (
  echo [错误] 项目启动失败，请查看 runtime-logs 目录。
  pause
  exit /b 1
)
echo 项目已启动。
echo.
echo ==================== 服务地址 ====================
echo [前端首页]       http://localhost:5173/
echo [本机管理页面]   http://localhost:8080/api/manage/
echo [后端 API]       http://localhost:8080/api
echo [后端接口文档]   http://localhost:8080/api/doc.html
echo [向量服务说明]   http://localhost:18001/
echo [向量健康检查]   http://localhost:18001/health
echo [向量接口文档]   http://localhost:18001/docs
echo ==================================================
echo.
if /I "%~1"=="--no-wait" exit /b 0
echo 此窗口会保持打开，请使用窗口右上角的关闭按钮手动关闭。
:keep_open
ping.exe -n 86400 127.0.0.1 >nul
goto keep_open
