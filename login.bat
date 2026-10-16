@echo off
setlocal
cd /d "%~dp0"

set "TARGET=%~1"
if "%TARGET%"=="" set "TARGET=all"

if "%~2"=="" (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0login.ps1" -Target "%TARGET%"
) else (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0login.ps1" -Target "%TARGET%" -Profile "%~2"
)
exit /b %ERRORLEVEL%
