@echo off
setlocal EnableExtensions
rem ============================================================================
rem Detached start for crawler-bootstrap. Call this bat from OUTSIDE the agent
rem session process tree (WMI Win32_Process.Create works well), so the java
rem process survives agent session exits. Logs: data\logs\crawler-service.log
rem
rem Spawn (any terminal):
rem   powershell -c "Invoke-CimMethod Win32_Process -MethodName Create -Arguments @{CommandLine='cmd /c D:\projs_r\rx-mimic\start-crawler-detached.bat'}"
rem Stop:
rem   netstat -ano | findstr :81   then   taskkill /F /PID <pid>
rem ============================================================================

set "ROOT_DIR=%~dp0"
set "JAVA_HOME=C:\Program Files\Java\jdk-21"
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [ERROR] JDK 21 not found: %JAVA_HOME%
  exit /b 1
)

set "PATH=%JAVA_HOME%\bin;%PATH%"
set "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1"
set "TARGET_IP=192.168.31.4"

set "LOG_DIR=%ROOT_DIR%data\logs"
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"
set "LOG_FILE=%LOG_DIR%\crawler-service.log"

if not exist "%ROOT_DIR%data" mkdir "%ROOT_DIR%data"
if not exist "%ROOT_DIR%temp" mkdir "%ROOT_DIR%temp"

set "JVM_OPENS=--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.net=ALL-UNNAMED --add-opens=java.base/java.util.concurrent=ALL-UNNAMED"
set "APP_ARGS=-Dapollo.config-service=http://%TARGET_IP%:8080 -Dapollo.meta=http://%TARGET_IP%:8080 -Denv=DEV -Dapp.redisUrl=%TARGET_IP%:6379 -Dapp.nameserverEndpoints=%TARGET_IP%:854 -Dapp.browser.downloadPath=./temp/ -Dapp.browser.diskDataPath=./data/chrome%%s/ -Dapp.browser.headless=false -Dapp.browser.playwrightChannel=chrome -Dapp.browser.humanInputEnabled=true"

cd /d "%ROOT_DIR%"
echo [%date% %time%] ==== detached start ==== >> "%LOG_FILE%"
java %JVM_OPENS% %APP_ARGS% -jar crawler-bootstrap\target\crawler-bootstrap-1.0.jar >> "%LOG_FILE%" 2>&1
echo [%date% %time%] ==== exited, code=%ERRORLEVEL% ==== >> "%LOG_FILE%"
