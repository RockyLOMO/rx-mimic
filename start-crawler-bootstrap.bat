@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "ROOT_DIR=%~dp0"
set "BOOT_MODULE=crawler-bootstrap"
set "ROOT_TARGET=%ROOT_DIR%target"
set "MODULE_TARGET=%ROOT_DIR%%BOOT_MODULE%\target"
set "RUN_MODE=%~1"

set "JAVA_HOME=C:\Program Files\Java\jdk-21"
set "MAVEN_CMD=D:\home\apache-maven-3.9.12\bin\mvn.cmd"
set "HOSTS_FILE=%SystemRoot%\System32\drivers\etc\hosts"
set "TARGET_IP=192.168.31.4"

if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [ERROR] JDK 21 not found: %JAVA_HOME%
  exit /b 1
)

if not exist "%MAVEN_CMD%" (
  set "MAVEN_CMD=mvn.cmd"
)

set "PATH=%JAVA_HOME%\bin;%PATH%"
set "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1"

call :ensure_host redis.internal
call :ensure_host cloud.f-li.cn
call :ensure_host apollo.meta
call :ensure_host config.f-li.cn

if not exist ".\data" mkdir ".\data"
if not exist ".\temp" mkdir ".\temp"
if /i "%RUN_MODE%"=="jd-union-orders-verify" (
  if not exist ".\data\jd-union\debug" mkdir ".\data\jd-union\debug"
)

set "JVM_OPENS=--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.net=ALL-UNNAMED --add-opens=java.base/java.util.concurrent=ALL-UNNAMED"
set "APP_ARGS=-Dapollo.config-service=http://%TARGET_IP%:8080 -Dapollo.meta=http://%TARGET_IP%:8080 -Denv=DEV -Dapp.redisUrl=%TARGET_IP%:6379 -Dapp.nameserverEndpoints=%TARGET_IP%:854 -Dapp.browser.downloadPath=./temp/ -Dapp.browser.diskDataPath=./data/chrome%%s/ -Dapp.browser.headless=false -Dapp.browser.playwrightChannel=chrome -Dapp.browser.humanInputEnabled=true"

if /i "%RUN_MODE%"=="jd-union-orders-verify" (
  set "APP_ARGS=%APP_ARGS% -Dapp.custom.jdUnion.debugEnabled=true -Dapp.custom.jdUnion.debugOutputDir=./data/jd-union/debug -Dapp.custom.jdUnion.outputPath=./data/jd-union/orders-output.jsonl"
)

if not exist "%ROOT_TARGET%" mkdir "%ROOT_TARGET%"

call :find_boot_jar "%ROOT_TARGET%"
if errorlevel 2 exit /b %ERRORLEVEL%

if not defined BOOT_JAR (
  echo [INFO] Boot jar not found under %ROOT_TARGET%, packaging first...
  call :package_boot_jar
  if errorlevel 1 exit /b !ERRORLEVEL!

  call :find_boot_jar "%ROOT_TARGET%"
  if errorlevel 2 exit /b !ERRORLEVEL!
)

if not defined BOOT_JAR (
  echo [ERROR] Boot jar still not found under %ROOT_TARGET%
  exit /b 1
)

echo [INFO] JAVA_HOME=%JAVA_HOME%
echo [INFO] Maven=%MAVEN_CMD%
echo [INFO] Boot jar=%BOOT_JAR%

if /i "%RUN_MODE%"=="jd-union-orders-verify" (
  echo [INFO] Start app, then call the order API after login:
  echo [INFO] POST http://127.0.0.1:8080/custom/jd-union/getPromotionOrders
  echo [INFO] Body: {"startTime":"2026-04-15","endTime":"2026-05-08","debugEnabled":true}
) else (
  echo [INFO] Starting %BOOT_MODULE%...
)

java %JVM_OPENS% %APP_ARGS% -jar "%BOOT_JAR%"
exit /b %ERRORLEVEL%

:find_boot_jar
set "SCAN_DIR=%~1"
set "BOOT_JAR="
set "BOOT_JAR_COUNT=0"

if not exist "%SCAN_DIR%" exit /b 0

for /f "delims=" %%F in ('dir /b /a:-d "%SCAN_DIR%\%BOOT_MODULE%-*.jar" 2^>nul ^| findstr /V /I /C:".original"') do (
  set /a BOOT_JAR_COUNT+=1
  set "BOOT_JAR=%SCAN_DIR%\%%F"
)

if !BOOT_JAR_COUNT! GTR 1 (
  echo [ERROR] Found multiple boot jars under %SCAN_DIR%:
  for /f "delims=" %%F in ('dir /b /a:-d "%SCAN_DIR%\%BOOT_MODULE%-*.jar" 2^>nul ^| findstr /V /I /C:".original"') do echo [ERROR]   %%F
  set "BOOT_JAR="
  exit /b 2
)

exit /b 0

:package_boot_jar
pushd "%ROOT_DIR%" || exit /b 1
echo [INFO] Running Maven package...
call "%MAVEN_CMD%" -pl "%BOOT_MODULE%" -am -DskipTests package
set "PACKAGE_EXIT_CODE=%ERRORLEVEL%"
popd

if not "%PACKAGE_EXIT_CODE%"=="0" exit /b %PACKAGE_EXIT_CODE%

call :find_module_boot_jar
if not "%ERRORLEVEL%"=="0" exit /b %ERRORLEVEL%

copy /Y "%MODULE_BOOT_JAR%" "%ROOT_TARGET%\" >nul
if not "%ERRORLEVEL%"=="0" exit /b %ERRORLEVEL%

echo [INFO] Boot jar copied to %ROOT_TARGET%
exit /b 0

:find_module_boot_jar
set "MODULE_BOOT_JAR="
set "MODULE_BOOT_JAR_COUNT=0"

for /f "delims=" %%F in ('dir /b /a:-d "%MODULE_TARGET%\%BOOT_MODULE%-*.jar" 2^>nul ^| findstr /V /I /C:".original"') do (
  set /a MODULE_BOOT_JAR_COUNT+=1
  set "MODULE_BOOT_JAR=%MODULE_TARGET%\%%F"
)

if "!MODULE_BOOT_JAR_COUNT!"=="1" exit /b 0

if "!MODULE_BOOT_JAR_COUNT!"=="0" (
  echo [ERROR] Maven package completed, but no boot jar found under %MODULE_TARGET%
  exit /b 1
)

echo [ERROR] Maven package completed, but multiple boot jars found under %MODULE_TARGET%:
for /f "delims=" %%F in ('dir /b /a:-d "%MODULE_TARGET%\%BOOT_MODULE%-*.jar" 2^>nul ^| findstr /V /I /C:".original"') do echo [ERROR]   %%F
exit /b 2

:ensure_host
set "HOST_NAME=%~1"
findstr /R /C:"^[ 	]*%TARGET_IP%[ 	][ 	]*%HOST_NAME%\>" "%HOSTS_FILE%" >nul 2>&1
if "%ERRORLEVEL%"=="0" (
  echo [INFO] hosts exists: %HOST_NAME% -^> %TARGET_IP%
  exit /b 0
)

net session >nul 2>&1
if not "%ERRORLEVEL%"=="0" (
  echo [WARN] hosts missing: %HOST_NAME% -^> %TARGET_IP%
  echo [WARN] Run this script as Administrator to auto append hosts entry.
  exit /b 0
)

>> "%HOSTS_FILE%" echo %TARGET_IP% %HOST_NAME%
echo [INFO] hosts added: %HOST_NAME% -^> %TARGET_IP%
exit /b 0
