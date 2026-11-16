try { [Console]::OutputEncoding=[System.Text.Encoding]::UTF8 } catch {}
$prior=Get-Process -Id 14092
$p='artifacts/validation/20261113-144238-timings/progress/soft-output'
$apk="$p/apk/test.apk"
$digest=(Get-FileHash $apk).Hash
"D01 content x10 after Q01 PID $($prior.Id); apk $digest" | Set-Content "$p/content-queue.txt"
$prior.WaitForExit()
if ((Get-Content "$p/q01-queue.txt" -Raw) -notmatch 'q01 exit=') {throw 'Q01 coordinator terminated unexpectedly'}
if ((Get-FileHash $apk).Hash -ne $digest) {throw 'Candidate APK changed'}
& pwsh -NoProfile -File tools/run-device-tests.ps1 -Serial 10.67.116.12:5555 -SkipBuild -ApkPath $apk -Suite content -Rounds 10 -EvidenceDirectory "$p/content" *> "$p/content.log"
"content exit=$LASTEXITCODE" | Add-Content "$p/content-queue.txt"

