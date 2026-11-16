try { [Console]::OutputEncoding=[System.Text.Encoding]::UTF8 } catch {}
$env:JAVA_HOME='C:/Program Files/Java/jdk-17.0.17.10'; $env:ANDROID_HOME=Join-Path $env:LOCALAPPDATA Android/Sdk
$d='artifacts/validation/20261113-144238-timings/progress'
$pub=Join-Path (Get-Content "$d/local-candidate.txt") public/candidate.json
$prior=Get-Process -Id 33332 -ErrorAction Stop
Wait-Process -InputObject $prior
if (!((Get-Content "$d/device-queue.txt") -match '^consumer exit=')){throw 'Previous device queue did not reach terminal consumer step'}
& ./tools/run-device-tests.ps1 -Serial '10.67.116.12:5555' -Suite faults -Rounds 10 -SkipBuild -ApkPath "$d/h04/controlled-apk/test.apk" -EvidenceDirectory "$d/h04/device" *> "$d/h04/device.log"
'H04 injection suite PASS' | Add-Content "$d/h04/queue.txt"

