try { [Console]::OutputEncoding=[System.Text.Encoding]::UTF8 } catch {}
$prior=Get-Process -Id 34852 -ErrorAction Stop
$root='artifacts/validation/20261113-144238-timings'
$d="$root/progress"
$serial='10.67.116.12:5555'
$apk="$d/apk/test.apk"
$env:ANDROID_HOME=Join-Path $env:LOCALAPPDATA Android/Sdk
"Waiting for baseline board runner $($prior.Id) $($prior.StartTime.ToString('o'))" | Set-Content "$d/device-queue.txt"
Wait-Process -InputObject $prior
if (!(Select-String -Quiet -Path 'artifacts/validation/20261113-132400-rc-board/followup.txt' -Pattern '^endurance exit=')){throw 'Earlier board owner ended unexpectedly'}
python tools/run-timings.py --serial $serial --apk $apk *> "$d/timings.log"
"timings exit=$LASTEXITCODE" | Add-Content "$d/device-queue.txt"
try { & ./tools/run-device-tests.ps1 -Serial $serial -Suite progress -SkipBuild -ApkPath "$d/before-apk/test.apk" -EvidenceDirectory "$d/before-progress" *> "$d/before-progress.log" } catch { $_.Exception.Message | Add-Content "$d/before-progress.log" }
if (!(Test-Path "$d/before-progress/instrumentation.txt")){throw 'Missing progress negative control result'}
$before=Get-Content "$d/before-progress/instrumentation.txt" -Raw
"before extra publications observed=$($before -match 'Post-stall progress publications=([2-9]|[1-9][0-9]+)')" | Add-Content "$d/device-queue.txt"
foreach ($suite in 'progress','diagnostics'){
    try { & ./tools/run-device-tests.ps1 -Serial $serial -Suite $suite -SkipBuild -ApkPath $apk -EvidenceDirectory "$d/current-$suite" *> "$d/current-$suite.log"
          "$suite PASS" | Add-Content "$d/device-queue.txt" }
    catch { "$suite ERROR $($_.Exception.Message)" | Add-Content "$d/device-queue.txt" }
}
python tools/verify-consumer-device.py --run "$d/delivery/run-20261113-150721-714587" --serial $serial --with-track-switch *> "$d/consumer-device.log"
"consumer exit=$LASTEXITCODE" | Add-Content "$d/device-queue.txt"
