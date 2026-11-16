[CmdletBinding(DefaultParameterSetName='device')]
param(
    [Parameter(ParameterSetName='device',Mandatory)][string] $Serial,
    [Parameter(ParameterSetName='build',Mandatory)][switch] $BuildOnly,
    [string] $AndroidSdk = $env:ANDROID_HOME
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $AndroidSdk) { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$bin = Join-Path $AndroidSdk 'ndk/30.0.16248370/toolchains/llvm/prebuilt/windows-x86_64/bin'
$cc = Join-Path $bin 'clang.exe'
$adb = Join-Path $AndroidSdk 'platform-tools/adb.exe'
function Device {
    & $adb -s $Serial @args
    if ($LASTEXITCODE) { throw "ADB returned $LASTEXITCODE" }
}
Push-Location $root
try {
    $out = Join-Path 'artifacts/validation' ("{0:yyyyMMdd-HHmmss}-s02-arm64" -f (Get-Date))
    New-Item -ItemType Directory -Path $out | Out-Null
    $binary = Join-Path $out 'rgba_checks'
    $libs = 'artifacts/n01/stripped/arm64-v8a'
    & $cc '--target=aarch64-linux-android23' '-std=c11' '-O2' '-Wall' '-Werror' '-fPIE' '-pie' '-Isdk/src/main/cpp' `
        '-Ithird_party/ffmpeg/8.1.3/include' 'native-tests/rgba_checks.c' 'sdk/src/main/cpp/rgba_renderer.c' `
        "-L$libs" '-lswscale' '-lavutil' '-lm' '-o' $binary
    if ($LASTEXITCODE) { throw "NDK build returned $LASTEXITCODE" }
    $files = @($binary, (Join-Path $libs 'libswscale.so'), (Join-Path $libs 'libavutil.so'))
    $digests = $files | ForEach-Object { "$((Get-FileHash -LiteralPath $_).Hash.ToLowerInvariant())  $(Split-Path $_ -Leaf)" }
    $digests | Set-Content -Encoding ascii (Join-Path $out 'SHA256SUMS')
    if ($BuildOnly) { "Built $out"; return }
    $remote = '/data/local/tmp/playback-rgba-' + [guid]::NewGuid().ToString('N')
    Device shell mkdir $remote
    try {
        foreach ($file in $files) {
            $name = Split-Path $file -Leaf
            Device push $file "$remote/$name"
            $digest = (Get-FileHash -LiteralPath $file).Hash.ToLowerInvariant()
            if ((Device shell sha256sum "$remote/$name") -notmatch "^$digest ") { throw "Digest mismatch for $name" }
        }
        Device shell chmod 700 "$remote/rgba_checks"
        @("serial=$Serial", (Device shell getprop ro.build.fingerprint), "page=$(Device shell getconf PAGE_SIZE)") |
            Set-Content -Encoding utf8 (Join-Path $out 'device.txt')
        Device shell env "LD_LIBRARY_PATH=$remote" "$remote/rgba_checks" | Tee-Object -FilePath (Join-Path $out 'native-test.txt')
    } finally {
        foreach ($file in $files) { Device shell rm -f "$remote/$(Split-Path $file -Leaf)" }
        Device shell rmdir $remote
    }
    "Evidence $out"
} finally { Pop-Location }
