param(
    [string]$Target = "all",
    [string]$Profile = ""
)

$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
# exec:java 跑在 Maven 进程里，不吃 surefire 的 argLine。少了这组参数，Java 21 会拒绝 rxlib 反射。
$jvmOpens = "--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.net=ALL-UNNAMED --add-opens=java.base/java.util.concurrent=ALL-UNNAMED"
if (-not $env:MAVEN_OPTS -or $env:MAVEN_OPTS -notlike "*java.base/java.util.concurrent*") {
    if ($env:MAVEN_OPTS) { $env:MAVEN_OPTS = "$env:MAVEN_OPTS $jvmOpens" } else { $env:MAVEN_OPTS = $jvmOpens }
}
$mvn = "D:\home\.shell\apache-maven\bin\mvn.cmd"
if (-not (Test-Path $mvn)) {
    $mvn = "mvn"
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir

$loginMutex = New-Object System.Threading.Mutex($false, "Local\rx-mimic-login")
$loginOwned = $false
try {
    $loginOwned = $loginMutex.WaitOne(0)
} catch [System.Threading.AbandonedMutexException] {
    $loginOwned = $true
}
if (-not $loginOwned) {
    Write-Host "[ERROR] 登录助手已在运行，不要重复打开 login.bat。" -ForegroundColor Red
    exit 1
}

try {
    Write-Host "================================================================================" -ForegroundColor Cyan
    Write-Host "               rx-mimic 统一登录助手 (JD & Taobao / Alimama)" -ForegroundColor Green
    Write-Host "================================================================================" -ForegroundColor Cyan
    $profileRoot = Join-Path $env:LOCALAPPDATA "rx-mimic\chrome"
    $profileName = "common"
    if ($Profile) { $profileName = $Profile }
    Write-Host "目标模式: $Target (可选: all, jd, tb)" -ForegroundColor Yellow
    Write-Host "Profile: $(Join-Path $profileRoot $profileName)" -ForegroundColor Gray
    Write-Host "不传 -Profile 时，登录、测试、服务共用这一份。请先停掉正在跑的爬虫服务。" -ForegroundColor Gray
    Write-Host "================================================================================" -ForegroundColor Cyan

    # 不用 Surefire。测试进程里 System.exit 会被拦截，30 秒后杀掉 JVM，Chrome Cookie 库写不完整。
    # 必须 -am，让 crawler-api 走本次反应器。分开跑 exec 时会去读本地仓库里父项目仍是 rx-mercury 的旧 pom。
    $execArgs = $Target
    if ($Profile) { $execArgs = "$Target $Profile" }
    & $mvn -B -pl crawler-bootstrap -am compile "org.codehaus.mojo:exec-maven-plugin:3.6.4:java" "-Dexec.mainClass=org.rx.crawler.tool.LoginHelper" "-Dexec.classpathScope=runtime" "-Dexec.args=$execArgs"
    $code = $LASTEXITCODE
    if ($code -ne 0) {
        Write-Host "[ERROR] 登录助手退出码 $code。Cookie 未确认写入，先不要用服务跑任务。" -ForegroundColor Red
        exit $code
    }
    exit 0
} finally {
    if ($loginOwned) {
        $loginMutex.ReleaseMutex() | Out-Null
        $loginMutex.Dispose()
    }
}
