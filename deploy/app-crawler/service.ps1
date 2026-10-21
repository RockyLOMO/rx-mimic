[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Initialize', 'Start', 'Stop', 'Health', 'Deploy', 'Rollback', 'VerifyRelease')]
    [string]$Action,
    [string]$Root = 'D:\rx-mimic',
    [string]$Version,
    [string]$TaskName = 'rx-mimic',
    [string]$Account = 'AnZhi',
    [string]$BaseUrl = 'http://127.0.0.1:81',
    [int]$DrainTimeoutSeconds = 900,
    [int]$HealthTimeoutSeconds = 120
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$Root = [IO.Path]::GetFullPath($Root).TrimEnd('\')
$run = Join-Path $Root 'run'
$statePath = Join-Path $run 'current.json'
$previousPath = Join-Path $run 'previous.json'
$pidPath = Join-Path $run 'process.json'
if (([uri]$BaseUrl).Host -notin @('127.0.0.1', 'localhost', '[::1]')) { throw 'Management must target loopback.' }
function Write-Json($Path, $Value) {
    $temp = "$Path.$([guid]::NewGuid().ToString('N')).tmp"
    [IO.File]::WriteAllText($temp, ($Value | ConvertTo-Json -Depth 10), (New-Object Text.UTF8Encoding $false))
    if (Test-Path -LiteralPath $Path) { [IO.File]::Replace($temp, $Path, "$Path.bak") }
    else { [IO.File]::Move($temp, $Path) }
}
function Read-Json($Path) { Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json }
function Read-ReleaseIndex {
    $path = Join-Path $Root 'run\release-index.json'
    $map = @{}
    $last = 0
    if (Test-Path -LiteralPath $path) {
        $raw = Read-Json $path
        if ($raw.PSObject.Properties['last']) { $last = [int]$raw.last }
        if ($raw.PSObject.Properties['map'] -and $raw.map) {
            foreach ($prop in $raw.map.PSObject.Properties) { $map[$prop.Name] = [int]$prop.Value }
        }
    }
    return @{ last = $last; map = $map }
}
function Save-ReleaseIndex($Index) {
    $map = [ordered]@{}
    foreach ($key in @($Index.map.Keys | Sort-Object)) { $map[$key] = [int]$Index.map[$key] }
    Write-Json (Join-Path $Root 'run\release-index.json') ([ordered]@{ last = [int]$Index.last; map = $map })
}
# Deploy assigns the next integer once per release SHA. Rollback and restart reuse it.
function Resolve-ReleaseNumber([string]$Sha, [bool]$Assign) {
    if ($Sha -notmatch '^[a-f0-9]{40}$') { throw 'Release number requires a 40-character lowercase hexadecimal release identifier.' }
    $index = Read-ReleaseIndex
    if ($index.map.ContainsKey($Sha)) { return [int]$index.map[$Sha] }
    if (-not $Assign) { return 0 }
    $number = [int]$index.last + 1
    $index.map[$Sha] = $number
    $index.last = $number
    Save-ReleaseIndex $index
    return $number
}
function Get-Release($Sha) {
    if ($Sha -notmatch '^[a-f0-9]{40}$') { throw 'Version must be a 40-character lowercase hexadecimal release identifier.' }
    $dir = Join-Path $Root "releases\$Sha"
    $jar = Join-Path $dir 'app.jar'
    $manifest = Read-Json (Join-Path $dir 'manifest.json')
    if ($manifest.version -ne $Sha) { throw 'Manifest version differs from release directory.' }
    if ($manifest.sha256 -notmatch '^[a-fA-F0-9]{64}$' -or
        (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -ne $manifest.sha256) { throw 'Release hash mismatch.' }
    $release = [ordered]@{ version = $Sha; jar = $jar; sha256 = $manifest.sha256 }
    # Keep local working-tree provenance when version is derived from source content.
    foreach ($field in @('sourceKind', 'baseCommit', 'sourceDigestSHA256')) {
        if ($manifest.PSObject.Properties[$field]) { $release[$field] = $manifest.$field }
    }
    return [pscustomobject]$release
}
function Get-OwnedProcess {
    if (!(Test-Path -LiteralPath $pidPath)) { return $null }
    $record = Read-Json $pidPath
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$([int]$record.pid)"
    if (!$process) { return $null }
    # PS 7 auto-parses ISO JSON dates; Windows PS 5.1 preserves strings.
    $recordTime = if ($record.startedAt -is [DateTime]) { $record.startedAt.ToUniversalTime() }
        else { [DateTimeOffset]::Parse($record.startedAt).UtcDateTime }
    if ($process.CreationDate.ToUniversalTime().Ticks -ne $recordTime.Ticks -or
        $process.Name -notmatch '^java(w)?\.exe$' -or
        !$process.CommandLine.Contains('"' + $record.jar + '"') -or
        !$record.jar.StartsWith($Root + '\releases\', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'PID identity/creation time/release ownership mismatch; refusing to stop.'
    }
    return $process
}
function Invoke-Management($Path, $Method = 'Get') {
    $key = $env:RX_MIMIC_ADMIN_KEY
    if (!$key) { $key = [Environment]::GetEnvironmentVariable('RX_MIMIC_ADMIN_KEY', 'User') }
    if (!$key) { throw 'Set RX_MIMIC_ADMIN_KEY in the deployment shell; never pass it on command line.' }
    $reply = Invoke-RestMethod -Uri ($BaseUrl.TrimEnd('/') + $Path) -Method $Method -Headers @{ Authorization = "Bearer $key" } -TimeoutSec 10
    if (!$reply.success) { throw "Management endpoint failed: $Path" }
    return $reply.data
}
function Wait-Healthy($Sha) {
    $deadline = [DateTime]::UtcNow.AddSeconds($HealthTimeoutSeconds)
    do {
        try {
            $live = Invoke-Management '/health/live'
            $ready = Invoke-Management '/health/ready'
            if ($live.version -eq $Sha -and $ready.version -eq $Sha -and !$ready.draining) { return $ready }
        } catch { Write-Verbose $_.Exception.Message }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Health/version check failed for $Sha."
}
function Enter-Drain {
    $null = Invoke-Management '/internal/deployment/drain' 'Post'
    $deadline = [DateTime]::UtcNow.AddSeconds($DrainTimeoutSeconds)
    do {
        $status = Invoke-Management '/internal/deployment/status'
        if ($status.draining -and $status.runningTasks -eq 0) { return }
        if ([DateTime]::UtcNow -ge $deadline) { throw 'Drain timed out; current tasks will not be killed.' }
        Start-Sleep -Seconds 2
    } while ($true)
}
function Assert-ProfileReleased {
    $profile = Join-Path $Root 'profiles'
    if (@(Get-CimInstance Win32_Process -Filter "Name='chrome.exe'" | Where-Object {
        $_.CommandLine -and $_.CommandLine.IndexOf($profile, [StringComparison]::OrdinalIgnoreCase) -ge 0
    }).Count) { throw 'Chrome still owns the service profile. Release it manually; no Chrome was killed.' }
}
function Assert-NoOrphanJava {
    if (@(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" | Where-Object {
        $_.CommandLine -and $_.CommandLine.IndexOf($Root + '\releases\', [StringComparison]::OrdinalIgnoreCase) -ge 0
    }).Count) { throw 'Untracked service Java process exists; inspect PID state before starting or switching.' }
}
function Stop-Owned {
    $process = Get-OwnedProcess
    if (!$process) { return }
    # Windows Stop-Process cannot guarantee Java shutdown hooks. The authenticated
    # endpoint closes Spring and its owner-thread browser pool before exiting.
    $null = Invoke-Management '/internal/deployment/shutdown' 'Post'
    Wait-Process -Id $process.ProcessId -Timeout 30 -ErrorAction SilentlyContinue
    if (Get-OwnedProcess) { throw 'Graceful shutdown timed out; refusing force kill or version switch.' }
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        try { Assert-ProfileReleased; return } catch {
            if ([DateTime]::UtcNow -ge $deadline) { throw }
            Start-Sleep -Seconds 1
        }
    } while ($true)
}
function Start-Registered {
    $task = Get-ScheduledTask -TaskName $TaskName
    if ($task.Principal.LogonType -ne 'Interactive' -or @($task.Actions).Count -ne 1 -or
        !$task.Actions[0].Arguments.Contains((Join-Path $Root 'scripts\service.ps1'))) {
        throw 'Scheduled task does not match the InteractiveToken service launcher.'
    }
    # Previous launcher waits for Java, so wait for the scheduler to observe its exit.
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    while ((Get-ScheduledTask -TaskName $TaskName).State -eq 'Running') {
        if ([DateTime]::UtcNow -ge $deadline) { throw 'Previous scheduled task launcher still running.' }
        Start-Sleep -Seconds 1
    }
    Start-ScheduledTask -TaskName $TaskName
}
if ($Action -eq 'Initialize') {
    if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) { throw 'Task already exists; refusing overwrite.' }
    if ((Test-Path -LiteralPath $Root) -and @(Get-ChildItem -LiteralPath $Root -Force).Count) {
        throw 'Runtime root is not empty; inspect its ownership and existing services before initialization.'
    }
    foreach ($dir in @('releases', 'config', 'data', 'profiles', 'logs', 'run', 'scripts')) {
        $null = New-Item -Path (Join-Path $Root $dir) -ItemType Directory -Force
    }
    $targetScript = Join-Path $Root 'scripts\service.ps1'
    if (Test-Path -LiteralPath $targetScript) { throw 'Installed launcher already exists; refusing overwrite.' }
    Copy-Item -LiteralPath $PSCommandPath -Destination $targetScript
    $arguments = '-NoLogo -NoProfile -ExecutionPolicy Bypass -File "{0}" -Action Start -Root "{1}"' -f $targetScript, $Root
    $taskAction = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $arguments -WorkingDirectory $Root
    $principal = New-ScheduledTaskPrincipal -UserId $Account -LogonType Interactive -RunLevel Limited
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $Account
    $settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
    Register-ScheduledTask -TaskName $TaskName -Action $taskAction -Principal $principal -Trigger $trigger -Settings $settings | Out-Null
    Write-Output 'Initialized InteractiveToken task. Configure YAML and environment secrets before deployment.'
    exit 0
}
if ($Action -eq 'VerifyRelease') { Get-Release $Version; exit 0 }
if ($Action -eq 'Start') {
    $startLock = [IO.File]::Open((Join-Path $run 'start.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
    if (Get-OwnedProcess) { throw 'Service already running.' }
    Assert-NoOrphanJava
    if ([Diagnostics.Process]::GetCurrentProcess().SessionId -eq 0 -or ![Environment]::UserInteractive) { throw 'Interactive desktop required.' }
    $state = Read-Json $statePath
    $release = Get-Release $state.version
    $config = Join-Path $Root 'config\application-prod.yml'
    if (!(Test-Path -LiteralPath $config)) { throw 'External configuration missing.' }
    $env:RX_MIMIC_VERSION = $release.version
    $env:RX_MIMIC_RELEASE = [string](Resolve-ReleaseNumber $state.version $false)
    if (!$env:RX_MIMIC_ADMIN_KEY) { $env:RX_MIMIC_ADMIN_KEY = [Environment]::GetEnvironmentVariable('RX_MIMIC_ADMIN_KEY', 'User') }
    if (!$env:RX_MIMIC_ADMIN_KEY) { throw 'Configure RX_MIMIC_ADMIN_KEY in the runtime account user environment.' }
    $env:PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD = '1'
    $java = (Get-Command java.exe -ErrorAction Stop).Source
    $args = @('-Xms192m', '-Xmx512m', '-XX:MaxDirectMemorySize=128m',
        '--add-opens=java.base/java.lang=ALL-UNNAMED', '--add-opens=java.base/java.io=ALL-UNNAMED',
        '--add-opens=java.base/java.net=ALL-UNNAMED', '--add-opens=java.base/java.util.concurrent=ALL-UNNAMED',
        '-Dfile.encoding=UTF-8', '-jar', ('"' + $release.jar + '"'), '--spring.profiles.active=prod',
        ('--spring.config.additional-location="file:' + $config.Replace('\', '/') + '"'))
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $process = Start-Process -FilePath $java -ArgumentList $args -WorkingDirectory $Root -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $Root "logs\$stamp.out.log") -RedirectStandardError (Join-Path $Root "logs\$stamp.err.log")
    $identity = Get-CimInstance Win32_Process -Filter "ProcessId=$($process.Id)"
    if (!$identity) { throw 'Java exited before PID registration.' }
    Write-Json $pidPath @{ pid = $process.Id; jar = $release.jar; version = $release.version; startedAt = $identity.CreationDate.ToUniversalTime().ToString('o') }
    $process.WaitForExit()
    exit $process.ExitCode
}
if ($Action -eq 'Health') { Wait-Healthy (Read-Json $statePath).version; exit 0 }
$lock = $null
$drained = $false
$old = $null
$switched = $false
try {
    $lock = [IO.File]::Open((Join-Path $run 'deployment.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
    if ($Action -eq 'Rollback') { $Version = (Read-Json $previousPath).version }
    if ($Action -ne 'Stop') { $next = Get-Release $Version }
    if (Test-Path -LiteralPath $statePath) {
        $old = Read-Json $statePath
        if (Get-OwnedProcess) {
            $drained = $true
            Enter-Drain
            Stop-Owned
        }
    } elseif (Test-Path -LiteralPath $pidPath) { throw 'PID state exists without a current release; inspect before deploying.' }
    if ($Action -eq 'Stop') { Write-Output 'Service stopped after drain.'; exit 0 }
    Assert-ProfileReleased
    Assert-NoOrphanJava
    if ($Action -eq 'Deploy') { Resolve-ReleaseNumber $Version $true | Out-Null }
    $switched = $true
    Write-Json $statePath $next
    Start-Registered
    $null = Wait-Healthy $Version
    if ($old) { Write-Json $previousPath $old }
    Write-Output "Deployment healthy: $Version"
} catch {
    $failure = $_
    if ($switched) {
        try {
            if (Get-OwnedProcess) { Enter-Drain; Stop-Owned }
            if (!$old) { throw 'No previous release; failed first deployment requires inspection.' }
            $null = Get-Release $old.version
            Write-Json $statePath $old
            Start-Registered
            $null = Wait-Healthy $old.version
            Write-Warning "Rolled back and healthy: $($old.version)"
        } catch { Write-Warning "Rollback failed: $($_.Exception.Message)" }
    } elseif ($drained) {
        try { $null = Invoke-Management '/internal/deployment/resume' 'Post' } catch { Write-Warning 'Could not resume service; operator action required.' }
    }
    throw $failure
} finally { if ($lock) { $lock.Dispose() } }
