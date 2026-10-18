@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"

set "KEYWORD=%~1"
if "%KEYWORD%"=="" set "KEYWORD=抽纸"

set "JAVA_HOME=C:\Program Files\Java\jdk-21"
set "MAVEN_OPTS=--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/java.net=ALL-UNNAMED --add-opens java.base/java.util.concurrent=ALL-UNNAMED"

echo ========================================================
echo Running Taobao 5-Step Integration Chain (Visible Chrome)
echo Keyword: %KEYWORD%
echo ========================================================

"D:\home\.shell\apache-maven\bin\mvn.cmd" -B -pl crawler-bootstrap -am "-DskipTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=TbUnionChainTests#tbUnionChainShouldSearchPromoteAndQueryOrders" "-Dtb.union.chain.integration=true" "-Dtb.union.chain.keyword=%KEYWORD%" test

set "CODE=%ERRORLEVEL%"
exit /b %CODE%
