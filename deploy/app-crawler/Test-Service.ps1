# Offline deployment state-machine checks. No tasks, Java, browser, network or live services are touched.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$service = Join-Path $PSScriptRoot 'service.ps1'
$source = Get-Content -LiteralPath $service -Raw
if ($source -notmatch 'New-ScheduledTaskTrigger -AtLogOn -User \$Account' -or
    $source -notmatch 'Register-ScheduledTask[^\r\n]*-Trigger \$trigger' -or
    $source -notmatch '-RestartCount 3 -RestartInterval \(New-TimeSpan -Minutes 1\)') {
    throw 'Interactive logon trigger or bounded restart configuration is missing.'
}
'PASS: task registration includes account logon trigger and three bounded one-minute restarts (static)'
$global:Fixture = Join-Path $env:TEMP ('ai\20261020\rx-mimic-deploy-test-' + [guid]::NewGuid().ToString('N'))
$global:OldVersion = 'a' * 40
$global:NextVersion = 'b' * 40
$global:MockStartedAt = [DateTime]::UtcNow
$global:MockRunning = $true
$global:MockDrain = $false
$global:MockBusy = $false
$global:MockFailVersion = ''
$global:MockFailAllHealth = $false
$global:MockRejectShutdown = $false
$global:MockShutdowns = 0
$env:RX_MIMIC_ADMIN_KEY = 'offline-test-only'
foreach ($dir in @('run','scripts',"releases\$global:OldVersion","releases\$global:NextVersion")) {
    $null = New-Item -Path (Join-Path $global:Fixture $dir) -ItemType Directory -Force
}
function Write-FixtureJson($Path, $Object) { $Object | ConvertTo-Json | Set-Content -LiteralPath $Path -Encoding UTF8 }
foreach ($sha in @($global:OldVersion, $global:NextVersion)) {
    $dir = Join-Path $global:Fixture "releases\$sha"
    Set-Content -LiteralPath (Join-Path $dir 'app.jar') -Value $sha -Encoding ASCII
    Write-FixtureJson (Join-Path $dir 'manifest.json') @{version=$sha; sha256=(Get-FileHash (Join-Path $dir 'app.jar')).Hash}
}
function Reset-Fixture {
    $global:MockRunning = $true
    $global:MockDrain = $false
    $global:MockBusy = $false
    $global:MockFailVersion = ''
    $global:MockFailAllHealth = $false
    $global:MockRejectShutdown = $false
    $global:MockShutdowns = 0
    $jar = Join-Path $global:Fixture "releases\$global:OldVersion\app.jar"
    Write-FixtureJson (Join-Path $global:Fixture 'run\current.json') @{version=$global:OldVersion; jar=$jar}
    Write-FixtureJson (Join-Path $global:Fixture 'run\process.json') @{pid=123456; jar=$jar; startedAt=$global:MockStartedAt.ToUniversalTime().ToString('o')}
}
function Get-CimInstance {
    param($ClassName, $Filter)
    if ($Filter -like 'ProcessId=*' -and $global:MockRunning) {
        $state = Get-Content (Join-Path $global:Fixture 'run\current.json') -Raw | ConvertFrom-Json
        [pscustomobject]@{ProcessId=123456; Name='java.exe'; CreationDate=$global:MockStartedAt; CommandLine=('java -jar "'+$state.jar+'"')}
    }
}
function Wait-Process { param($Id,$Timeout,$ErrorAction) }
function Get-ScheduledTask {
    param($TaskName)
    [pscustomobject]@{State='Ready'; Principal=[pscustomobject]@{LogonType='Interactive'}; Actions=@([pscustomobject]@{Arguments=(Join-Path $global:Fixture 'scripts\service.ps1')})}
}
function Start-ScheduledTask {
    param($TaskName)
    $global:MockRunning=$true
    $global:MockDrain=$false
    $state=Get-Content (Join-Path $global:Fixture 'run\current.json') -Raw | ConvertFrom-Json
    Write-FixtureJson (Join-Path $global:Fixture 'run\process.json') @{pid=123456; jar=$state.jar; startedAt=$global:MockStartedAt.ToUniversalTime().ToString('o')}
}
function Invoke-RestMethod {
    param($Uri,$Method,$Headers,$TimeoutSec)
    $path=([uri]$Uri).AbsolutePath
    if ($path -eq '/internal/deployment/drain') {$global:MockDrain=$true}
    if ($path -eq '/internal/deployment/resume') {$global:MockDrain=$false}
    if ($path -eq '/internal/deployment/shutdown') {
        if ($global:MockRejectShutdown) {throw 'Simulated shutdown rejection'}
        $global:MockRunning=$false; $global:MockShutdowns++
    }
    $state=Get-Content (Join-Path $global:Fixture 'run\current.json') -Raw | ConvertFrom-Json
    if ($path -like '/health/*' -and ($global:MockFailAllHealth -or $state.version -eq $global:MockFailVersion)) {throw 'Simulated health failure'}
    [pscustomobject]@{success=$true;data=[pscustomobject]@{version=$state.version;draining=$global:MockDrain;runningTasks=[int]$global:MockBusy}}
}
function Assert-True($Condition,$Message) { if (!$Condition) {throw $Message} }
function Expect-Failure($Body,$Pattern) {
    $caught=$false
    try {& $Body} catch {
        $caught=$true
        Assert-True ($_.Exception.Message -like $Pattern) ('Unexpected failure: '+$_.Exception.Message)
    }
    Assert-True $caught 'Expected failure was not raised.'
}
Reset-Fixture
& $service -Action Deploy -Root $global:Fixture -Version $global:NextVersion -HealthTimeoutSeconds 0
Assert-True ((Get-Content (Join-Path $global:Fixture 'run\current.json') -Raw | ConvertFrom-Json).version -eq $global:NextVersion) 'New release was not activated.'
Assert-True ($global:MockShutdowns -eq 1) 'Expected exactly one graceful shutdown.'
$index = Get-Content (Join-Path $global:Fixture 'run\release-index.json') -Raw | ConvertFrom-Json
Assert-True ($index.last -eq 1 -and $index.map.($global:NextVersion) -eq 1) 'First publish did not assign release 1.'
$third = 'c' * 40
$thirdDir = Join-Path $global:Fixture "releases\$third"
$null = New-Item -Path $thirdDir -ItemType Directory -Force
Set-Content -LiteralPath (Join-Path $thirdDir 'app.jar') -Value $third -Encoding ASCII
Write-FixtureJson (Join-Path $thirdDir 'manifest.json') @{version=$third; sha256=(Get-FileHash (Join-Path $thirdDir 'app.jar')).Hash}
& $service -Action Deploy -Root $global:Fixture -Version $third -HealthTimeoutSeconds 0
$index = Get-Content (Join-Path $global:Fixture 'run\release-index.json') -Raw | ConvertFrom-Json
Assert-True ($index.last -eq 2 -and $index.map.$third -eq 2 -and $index.map.($global:NextVersion) -eq 1) 'Second publish did not stack release 2.'
& $service -Action Rollback -Root $global:Fixture -HealthTimeoutSeconds 0
$index = Get-Content (Join-Path $global:Fixture 'run\release-index.json') -Raw | ConvertFrom-Json
Assert-True ($index.last -eq 2) 'Rollback changed the release sequence.'
'PASS: hash validation, drain, graceful shutdown, version activation and health'
Reset-Fixture
$global:MockBusy=$true
Expect-Failure {& $service -Action Deploy -Root $global:Fixture -Version $global:NextVersion -DrainTimeoutSeconds 0} '*Drain timed out*'
Assert-True (!$global:MockDrain -and $global:MockRunning -and $global:MockShutdowns -eq 0) 'Drain timeout changed the running process or did not resume.'
'PASS: busy drain times out and resumes without stopping tasks'
Reset-Fixture
$global:MockFailVersion=$global:NextVersion
Expect-Failure {& $service -Action Deploy -Root $global:Fixture -Version $global:NextVersion -HealthTimeoutSeconds 0} '*Health/version check failed*'
Assert-True ((Get-Content (Join-Path $global:Fixture 'run\current.json') -Raw | ConvertFrom-Json).version -eq $global:OldVersion) 'Rollback did not restore the old release.'
Assert-True ($global:MockRunning -and !$global:MockDrain) 'Rolled back release is not running.'
'PASS: health failure rolls back and still reports deployment failure'
Reset-Fixture
$global:MockFailAllHealth=$true
Expect-Failure {& $service -Action Deploy -Root $global:Fixture -Version $global:NextVersion -HealthTimeoutSeconds 0} '*Health/version check failed*'
'PASS: rollback health failure remains a deployment failure with explicit warning'
Reset-Fixture
$global:MockRejectShutdown=$true
Expect-Failure {& $service -Action Deploy -Root $global:Fixture -Version $global:NextVersion} '*shutdown rejection*'
Assert-True ($global:MockRunning -and !$global:MockDrain -and $global:MockShutdowns -eq 0) 'Rejected shutdown must preserve the original service.'
'PASS: rejected graceful shutdown does not force kill or change release'
Reset-Fixture
$recordPath=Join-Path $global:Fixture 'run\process.json'
$record=Get-Content $recordPath -Raw | ConvertFrom-Json
$record.startedAt=$global:MockStartedAt.AddMinutes(-1).ToString('o')
Write-FixtureJson $recordPath $record
Expect-Failure {& $service -Action Deploy -Root $global:Fixture -Version $global:NextVersion} '*ownership mismatch*'
Assert-True ($global:MockShutdowns -eq 0 -and !$global:MockDrain) 'Stale PID record affected a process.'
'PASS: PID reuse/creation time mismatch fails before any stop'
Reset-Fixture
Add-Content (Join-Path $global:Fixture "releases\$global:NextVersion\app.jar") 'tamper'
Expect-Failure {& $service -Action Deploy -Root $global:Fixture -Version $global:NextVersion} '*hash mismatch*'
Assert-True ($global:MockShutdowns -eq 0 -and !$global:MockDrain) 'Bad hash affected the running release.'
'PASS: corrupted artifact is rejected before drain'
Expect-Failure {& $service -Action Deploy -Root $global:Fixture -Version '..\escape'} '*40-character lowercase hexadecimal release identifier*'
'PASS: release path traversal rejected'
Write-Output "Offline fixtures retained at $global:Fixture"
