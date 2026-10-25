@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"

set "TARGET=%~1"
if "%TARGET%"=="" set "TARGET=all"
set "LOCK=%TEMP%\rx-mimic-login.lock"

set "CODE=0"
(
    9>"%LOCK%" (
        if "%~2"=="" (
            powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-login.ps1" -Target "%TARGET%"
        ) else (
            powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-login.ps1" -Target "%TARGET%" -Profile "%~2"
        )
        set "CODE=!ERRORLEVEL!"
        ver >nul
    )
) || (
    echo [ERROR] start-login.bat is already running. Please do not open multiple instances.
    set "CODE=1"
)

echo.
if "!CODE!"=="0" (
    echo [INFO] Login helper finished [exit code: 0].
) else (
    echo [ERROR] Login helper failed or was interrupted [exit code: !CODE!].
)
echo.
if not defined NO_PAUSE pause
exit /b !CODE!
