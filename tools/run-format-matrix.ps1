[CmdletBinding()]
param(
    [Parameter(Mandatory)][string] $Serial,
    [string] $ApkPath
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $repo
try {
    if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
    if (-not $ApkPath) {
        & .\gradlew.bat --offline :sdk:assembleDebugAndroidTest
        if ($LASTEXITCODE) { throw 'Test build failed' }
        $ApkPath = 'sdk/build/outputs/apk/androidTest/debug/sdk-debug-androidTest.apk'
    }
    $apk = Get-Item -LiteralPath $ApkPath
    $id = (Get-FileHash $apk).Hash.ToLowerInvariant()
    $dir = Join-Path 'artifacts/validation' ("matrix-{0:yyyyMMdd-HHmmss}" -f (Get-Date))
    New-Item -ItemType Directory $dir | Out-Null
    $pinned = Join-Path $dir 'test.apk'
    Copy-Item $apk.FullName $pinned
    $samples = @('V01.mp4','V02.mov','V03.mp4','V04.mkv','V05.mkv','V06.webm','V07.webm',
                 'V08.avi','V09a.wmv','V09b.wmv','V10.ts','V11.mp4','A01.mp3','A02.m4a',
                 'A03.flac','A04.wav','A05a.ogg','A05b.ogg','A06.wma')
    # D01 confirmed component families. Scope the HW-only portion to those formats.
    $hardware = @('V01.mp4','V02.mov','V03.mp4','V04.mkv','V05.mkv','V06.webm','V07.webm','V10.ts','V11.mp4')
    $plan = foreach ($kind in @('file','uri','fd')) {
        foreach ($policy in @('AUTO','SOFTWARE_ONLY','HARDWARE_ONLY')) {
            $variants = if ($policy -eq 'HARDWARE_ONLY') { $hardware } else { $samples }
            foreach ($sample in $variants) { "$kind $policy $sample" }
        }
    }
    $plan | Set-Content (Join-Path $dir 'plan.txt')
    $reports = @()
    foreach ($kind in @('file','uri','fd')) {
        foreach ($policy in @('AUTO','SOFTWARE_ONLY','HARDWARE_ONLY')) {
            $variants = if ($policy -eq 'HARDWARE_ONLY') { $hardware } else { $samples }
            try {
                $out = & "$PSScriptRoot/run-device-tests.ps1" -Serial $Serial -Suite formats -SkipBuild -ApkPath $pinned -InputKind $kind -Policy $policy -Samples $variants
                $run = $out | Select-String '^Evidence: '
                $reports += "$kind $policy $($run.Line)"
            } catch {
                $reports += "$kind $policy FAILED $($_.Exception.Message)"
            }
            $reports | Set-Content (Join-Path $dir 'runs.txt')
        }
    }
    "$id  test.apk" | Set-Content (Join-Path $dir 'SHA256SUMS')
    if ($reports -match 'FAILED') { throw "Incomplete matrix: $dir" }
    & python "$PSScriptRoot/audit-format-matrix.py" $dir | Set-Content (Join-Path $dir 'verified.json')
    if ($LASTEXITCODE -ne 0) { throw "Matrix plan and evidence differ: $dir" }
    "Evidence: $dir"
} finally { Pop-Location }
