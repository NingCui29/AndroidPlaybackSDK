[CmdletBinding()]
param(
    [Parameter(Mandatory)][string] $Serial,
    [ValidateSet('player','full','input','ranges','hardware','formats','faults','content','diagnostics','progress','degrade','geometry','dynamic','audioRecovery','pcmWrites','audioTracks','timestamps','surface','surfaceBarrier','transferFault','providerCleanup','q01','q03','q04Realtime','q04V01','q04A03','q04Pilot','q04Release')][string] $Suite = 'player',
    [string] $AndroidSdk = $env:ANDROID_HOME,
    [switch] $SkipBuild,
    [string] $ApkPath = 'sdk/build/outputs/apk/androidTest/debug/sdk-debug-androidTest.apk',
    [string[]] $Fixtures,
    [ValidateSet('file','uri','fd')][string] $InputKind = 'file',
    [ValidateSet('AUTO','SOFTWARE_ONLY','HARDWARE_ONLY')][string] $Policy = 'AUTO',
    [int] $Rounds = 1,
    [string[]] $Samples,
    [string] $EvidenceDirectory
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $AndroidSdk) { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$env:ANDROID_HOME = $AndroidSdk
$adb = Join-Path $AndroidSdk 'platform-tools/adb.exe'
function Device {
    & $adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) { throw "adb failed ($LASTEXITCODE)" }
}
# 设备真机测试自动化运行脚本
# 负责安装测试 APK、校验固件属性、同步测试素材并触发 Instrumentation 用例
function DeviceFileHash([string]$path) {
    # 静默获取设备端指定文件的 SHA-256 校验和，若文件不存在则返回空字符串
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    try {
        $line = (& $adb -s $Serial shell run-as $package sha256sum $path 2>$null) -join ' '
        if ($line -match '^([a-fA-F0-9]{64})\s') { return $Matches[1] }
    } finally {
        $ErrorActionPreference = $prev
    }
    return ""
}
Push-Location $repo
try {
    if (-not $SkipBuild) {
        & .\gradlew.bat --offline :sdk:assembleDebugAndroidTest
        if ($LASTEXITCODE -ne 0) { throw 'Test APK build failed' }
    }
    $apk = Get-Item -LiteralPath $ApkPath
    $package = 'com.cn.media.playback.test'
    $active = Device shell dumpsys activity processes
    if ($active -match 'mClass=ComponentInfo\{com\.cn\.media\.playback\.test/[^}]+\}\s+mFinished=false') {
        throw "Device $Serial is running a playback test. Retry after the current instrumentation finishes."
    }
    Device install -r -t $apk.FullName
    $paths = @(Device shell pm path $package | ForEach-Object { $_.Trim() })
    if ($paths.Count -ne 1 -or $paths[0] -notmatch '^package:(/data/app/[^\s]+\.apk)$') {
        throw "Installed standalone instrumentation package has an unexpected path: $paths"
    }
    $installedPath = $Matches[1]
    $installedHash = DeviceFileHash $installedPath
    $localHash = (Get-FileHash -LiteralPath $apk.FullName).Hash.ToLowerInvariant()
    if ($installedHash -ne $localHash) { throw 'Installed instrumentation APK digest differs from the selected file' }
    Device shell run-as $package mkdir -p cache
    if (-not $Fixtures) {
        $Fixtures = switch ($Suite) {
            'dynamic' { 'H02-h264-inband.ts','H02-hevc-inband.ts','H02-h264-stsd.mp4','H02-hevc-stsd.mp4','H02-bad-length.mp4','V11.mp4' }
            'geometry' { 'V02.mov','V11.mp4' }
            'timestamps' { 'V10-forward.ts','V10-backward.ts' }
            'full' { 'V01.mp4' }
            'ranges' { 'V01.mp4' }
            { $_ -in 'q04Realtime','q04V01','q04Release' } { 'V01.mp4' }
            { $_ -in 'q04A03','q04Pilot' } { 'A03.flac' }
            'input' { 'V11.mp4' }
            'content' { 'A04.wav' }
            'diagnostics' { 'A04.wav' }
            'progress' { 'A04.wav' }
            'audioRecovery' { 'A04.wav' }
            'pcmWrites' { 'A04.wav','A02.m4a' }
            'audioTracks' { 'V05.mkv','L01-delayed.mkv' }
            'transferFault' { 'V11.mp4' }
            { $_ -in 'surfaceBarrier','providerCleanup' } { break }
            'q01' { 'V01.mp4','V11.mp4','A02.m4a' }
            'q03' { 'V05.mkv','V01.mp4','A04.wav' }
            'degrade' { 'bad-audio.mov','bad-video.mov','bad-both.mov','A02.m4a' }
            'hardware' { 'V01.mp4','V03.mp4','V10.ts','V11.mp4','H02-hevc.ts' }
            'faults' { 'V01.mp4' }
            'formats' {
                'V01.mp4','V11.mp4','A02.m4a','A04.wav'
                (Get-Content testdata/formats/manifest.json -Raw | ConvertFrom-Json).files.PSObject.Properties.Name
            }
            default { 'V01.mp4','V11.mp4' }
        }
    }
    $digests = @()
    foreach ($name in $Fixtures) {
        if ($name -notmatch '^[A-Za-z0-9_.-]+$') { throw "Invalid fixture basename" }
        $file = @('testdata/baseline','testdata/formats','testdata/faults','testdata/hardware','testdata/timestamps') | ForEach-Object { Join-Path $_ $name } |
                Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
        if (-not $file) { throw "Missing fixture $name" }
        $digest = (Get-FileHash $file).Hash.ToLowerInvariant()
        $digests += "$digest  $name"
        if ((DeviceFileHash "cache/$name") -ne $digest) {
            $staging = '/data/local/tmp/playback-fixture-' + [guid]::NewGuid().ToString('N')
            try {
                Device push $file $staging
                Device shell run-as $package cp $staging "cache/$name"
                if ((DeviceFileHash "cache/$name") -ne $digest) { throw "Device hash mismatch for $name" }
            } finally {
                Device shell rm -f $staging
            }
        }
    }
    $dir = if ($EvidenceDirectory) { $EvidenceDirectory } else {
        Join-Path 'artifacts/validation' ("{0:yyyyMMdd-HHmmss}-$Suite" -f (Get-Date))
    }
    if (Test-Path -LiteralPath $dir) { throw "Evidence directory already exists: $dir" }
    New-Item -ItemType Directory -Path $dir | Out-Null
    $digests | Set-Content (Join-Path $dir 'fixtures.sha256') -Encoding ascii
    $page = ((& $adb -s $Serial shell getconf PAGE_SIZE 2>&1) -join ' ').Trim()
    if ($page -notmatch '^\d+$') {
        $maps = (Device shell cat /proc/self/smaps) -join "`n"
        if ($maps -notmatch '(?m)^KernelPageSize:\s+(\d+) kB') { throw 'Cannot determine kernel page size' }
        $page = [int]$Matches[1] * 1024
    }
    [ordered]@{ package = $package; devicePath = $installedPath; apkSha256 = $installedHash.ToLowerInvariant() } |
        ConvertTo-Json | Set-Content (Join-Path $dir 'installed-apk.json')
    @("test-apk SHA256 $installedHash", (Device shell getprop ro.build.fingerprint),
      "API $(Device shell getprop ro.build.version.sdk)", "ABI $(Device shell getprop ro.product.cpu.abi)",
      "page size $page") | Set-Content (Join-Path $dir 'device.txt')
    $extras = @('-e','suite',$Suite,'-e','input',$InputKind,'-e','policy',$Policy,'-e','rounds',"$Rounds")
    if ($Samples) { $extras += @('-e','samples',($Samples -join ',')) }
    if ($Suite -like 'q04*') {
        "suite=$Suite`npolicy=$Policy`nrounds=$Rounds`nserial=$Serial" | Set-Content (Join-Path $dir 'plan.txt')
        Device shell dumpsys battery | Set-Content (Join-Path $dir 'battery.txt')
        Device shell dumpsys display | Select-String 'DisplayDeviceInfo|mActiveMode|refreshRate' |
                ForEach-Object Line | Set-Content (Join-Path $dir 'display.txt')
        Device shell cat /proc/loadavg | Set-Content (Join-Path $dir 'loadavg-before.txt')
        "Evidence: $dir"
        $reportPath = Join-Path $dir 'instrumentation.txt'
        # Stream the trace directory immediately so that partial measurements remain recoverable.
        & $adb -s $Serial shell am instrument -w @extras "$package/com.cn.media.playback.InputChecks" |
                Tee-Object -FilePath $reportPath
        $adbStatus = $LASTEXITCODE
        $result = Get-Content -LiteralPath $reportPath
        $traceMatch = [regex]::Match(($result -join "`n"), '(?m)^INSTRUMENTATION_(?:STATUS|RESULT): trace=(q04-[0-9]+)\s*$')
        if ($traceMatch.Success) {
            $traceDir = $traceMatch.Groups[1].Value
            foreach ($name in 'samples.csv','events.csv','rendered.csv','inventory.tsv') {
                & $adb -s $Serial exec-out run-as $package cat "cache/$traceDir/$name" > (Join-Path $dir $name)
                if ($LASTEXITCODE -ne 0) { throw "Trace read failed: $traceDir/$name" }
            }
            & python (Join-Path $repo 'tools/summarize-q04.py') $dir
            $scoreStatus = $LASTEXITCODE
        } else { throw "Q04 process did not report its trace directory: $dir" }
        if ($adbStatus -ne 0) { throw "ADB instrumentation connection terminated: $dir" }
    } else {
        $result = Device shell am instrument -w @extras "$package/com.cn.media.playback.InputChecks"
        $result | Set-Content (Join-Path $dir 'instrumentation.txt')
        if ($Suite -eq 'q03') {
            Device exec-out run-as $package cat cache/q03-events.tsv > (Join-Path $dir 'events.tsv')
        }
    }
    $result
    if (($result -join [Environment]::NewLine) -notmatch 'INSTRUMENTATION_CODE: -1') {
        throw "Failed device test. See $dir"
    }
    if ($Suite -like 'q04*' -and $scoreStatus -ne 0) {
        if ($Suite -eq 'q04Release' -and $scoreStatus -eq 2 -and (Test-Path -LiteralPath (Join-Path $dir 'summary.json'))) {
            Write-Warning "Q04 release evidence is incomplete. See $dir/summary.json"
            exit 2
        }
        throw "Q04 measured gate or evidence failure: $dir"
    }
    if ($Suite -eq 'faults') {
        & python (Join-Path $repo 'tools/score-h04-faults.py') $dir --rounds $Rounds
        if ($LASTEXITCODE -ne 0) { throw "Hardware recovery evidence or timing gate failed: $dir" }
    }
    "Evidence: $dir"
} finally {
    Pop-Location
}
