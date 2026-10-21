@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "PLATFORM=%~1"
set "KEYWORD=%~2"
if /i "%PLATFORM%"=="jd" goto run
if /i "%PLATFORM%"=="tb" goto run
echo [ERROR] Usage: start-test-chain.bat jd^|tb [keyword]
echo [ERROR] jd = Jingdong full chain. tb = Taobao full chain.
echo [ERROR] Omit keyword to use the test default. Promotion slot is 0. Allowed slots are 0, 1, 2, 3.
echo [ERROR] Stop the crawler service first. This opens the shared Chrome profile.
exit /b 1

:run
call :resolve_tools
if errorlevel 1 exit /b 1

echo ========================================================
echo Visible Chrome full chain: %PLATFORM%
echo Promotion slot: 0
if not "%KEYWORD%"=="" echo Keyword: %KEYWORD%
echo ========================================================

if /i "%PLATFORM%"=="jd" (
  set "TEST=JdUnionPromotionTaskTests#jdUnionChainShouldSearchPromoteAndOpenDetail"
  set "SWITCH=-Djd.union.chain.integration=true"
  set "SITE=-Djd.union.adSiteName=0"
  set "WORDPROP=-Djd.union.chain.keyword=%KEYWORD%"
) else (
  set "TEST=TbUnionChainTests#tbUnionChainShouldSearchPromoteAndQueryOrders"
  set "SWITCH=-Dtb.union.chain.integration=true"
  set "SITE=-Dtb.union.adSiteName=0"
  set "WORDPROP=-Dtb.union.chain.keyword=%KEYWORD%"
)

if "%KEYWORD%"=="" (
  "%MVN%" -B -pl crawler-bootstrap -am "-DskipTests=false" "-Dmaven.test.skip=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=%TEST%" "%SWITCH%" "%SITE%" test
) else (
  "%MVN%" -B -pl crawler-bootstrap -am "-DskipTests=false" "-Dmaven.test.skip=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=%TEST%" "%SWITCH%" "%SITE%" "%WORDPROP%" test
)
exit /b %ERRORLEVEL%

:resolve_tools
set "JAVA_HOME=C:\Program Files\Java\jdk-21"
if exist "%JAVA_HOME%\bin\java.exe" goto jdk_ok
set "JAVA_HOME=D:\home\.app\scoop\apps\temurin21-jdk\current"
if exist "%JAVA_HOME%\bin\java.exe" goto jdk_ok
echo [ERROR] JDK 21 not found.
exit /b 1
:jdk_ok
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "MAVEN_OPTS=--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.net=ALL-UNNAMED --add-opens=java.base/java.util.concurrent=ALL-UNNAMED"
set "MVN=D:\home\.app\scoop\apps\maven\current\bin\mvn.cmd"
if exist "%MVN%" exit /b 0
set "MVN=D:\home\.shell\apache-maven\bin\mvn.cmd"
if exist "%MVN%" exit /b 0
set "MVN=mvn.cmd"
exit /b 0
