@echo off
taskkill /F /T /im java.exe
timeout /t 2

cd c:\app-crawler\

if exist "app.jar.publish" (
	move /Y "app.jar" "app.jar.latest"
	timeout /t 2
	move /Y "app.jar.publish" "app.jar"
	timeout /t 2
)

set JDK_OPTIONS=--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.net=ALL-UNNAMED --add-opens=java.base/java.util.concurrent=ALL-UNNAMED
set MEM_OPTIONS=-Xms192m -Xmx384m -Xss512k -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=192m -XX:MaxDirectMemorySize=128m -Dapollo.configService=http://cloud.f-li.cn:8080 -Denv=pro
java %JDK_OPTIONS% -javaagent:.\jmx_prometheus_javaagent.jar=8080:.\jmx_prometheus_config.yaml %MEM_OPTIONS% -Dspring.profiles.active=prd -Dfile.encoding=UTF-8 -jar app.jar

