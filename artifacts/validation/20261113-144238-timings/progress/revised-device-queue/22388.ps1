try { [Console]::OutputEncoding=[System.Text.Encoding]::UTF8 } catch {}
$prior=Get-Process -Id 38024
$p='artifacts/validation/20261113-144238-timings/progress/soft-output'
$apk="$p/apk/test.apk"
$digest=(Get-FileHash $apk).Hash
"Q01 D01, SOFTWARE_ONLY, A02/V11/V01 x3; waiting for endurance coordinator $($prior.Id); apk $digest" | Set-Content "$p/q01-queue.txt"
$prior.WaitForExit()
if (!(Test-Path "$p/endurance/summary.json")) {throw 'Endurance coordinator exited without a final report'}
if ((Get-FileHash $apk).Hash -ne $digest) {throw 'Test APK changed'}
& pwsh -NoProfile -File tools/run-device-tests.ps1 -Serial 10.67.116.12:5555 -SkipBuild -ApkPath $apk -Suite q01 -Policy SOFTWARE_ONLY -Rounds 3 -EvidenceDirectory "$p/q01" *> "$p/q01.log"
"q01 exit=$LASTEXITCODE" | Add-Content "$p/q01-queue.txt"

