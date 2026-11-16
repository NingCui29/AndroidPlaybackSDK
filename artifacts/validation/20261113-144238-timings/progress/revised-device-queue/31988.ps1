try { [Console]::OutputEncoding=[System.Text.Encoding]::UTF8 } catch {}
$env:JAVA_HOME='C:/Program Files/Java/jdk-17.0.17.10'; $env:ANDROID_HOME=Join-Path $env:LOCALAPPDATA Android/Sdk
$root='artifacts/validation/20261113-144238-timings/progress'; $d="$root/soft-output"; $pub=Join-Path (Get-Content "$root/local-candidate.txt") public/candidate.json
$prior=Get-Process -Id 32112 -ErrorAction Stop
"Waiting for H04 runner $($prior.Id) started $($prior.StartTime.ToString('o'))" | Set-Content "$d/device-queue.txt"
Wait-Process -InputObject $prior
if (!(Test-Path "$root/h04/device/instrumentation.txt") -or !((Get-Content "$root/h04/device/instrumentation.txt") -match '^INSTRUMENTATION_CODE:')){throw 'Missing terminal result for preceding board run'}
foreach ($suite in 'pcmWrites','geometry') {
    try { & ./tools/run-device-tests.ps1 -Serial '10.67.116.12:5555' -Suite $suite -SkipBuild -ApkPath "$d/apk/test.apk" -EvidenceDirectory "$d/$suite" *> "$d/$suite.log"
          "$suite PASS" | Add-Content "$d/device-queue.txt" } catch { "$suite ERROR $($_.Exception.Message)" | Add-Content "$d/device-queue.txt" }
}

