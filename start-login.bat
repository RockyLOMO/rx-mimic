@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"

set "TARGET=%~1"
if "%TARGET%"=="" set "TARGET=all"
set "LOCK=%TEMP%\rx-mimic-login.lock"

2>nul (
    9>"%LOCK%" (
        if "%~2"=="" (
            powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0login.ps1" -Target "%TARGET%"
        ) else (
            powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0login.ps1" -Target "%TARGET%" -Profile "%~2"
        )
        set "CODE=!ERRORLEVEL!"
        ver >nul
    )
) || (
    echo [ERROR] start-login.bat is already running.
    exit /b 1
)
exit /b !CODE!
