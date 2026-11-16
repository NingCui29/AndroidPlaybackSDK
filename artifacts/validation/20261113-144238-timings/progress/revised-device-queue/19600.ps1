try { [Console]::OutputEncoding=[System.Text.Encoding]::UTF8 } catch {}
$parentRun=Get-Process -Id 17404
$p='artifacts/validation/20261113-144238-timings/progress/soft-output'
$apk="$p/apk/test.apk"
$sha=(Get-FileHash $apk).Hash
"ranges D01 after audioRecovery PID $($parentRun.Id); apk $sha" | Set-Content "$p/ranges-queue.txt"
$parentRun.WaitForExit()
if ((Get-Content "$p/audio-queue.txt" -Raw) -notmatch 'audioRecovery exit=') {throw 'Prior suite queue did not finish'}
if ((Get-FileHash $apk).Hash -ne $sha) {throw 'Candidate APK changed'}
& pwsh -NoProfile -File tools/run-device-tests.ps1 -Serial 10.67.116.12:5555 -SkipBuild -ApkPath $apk -Suite ranges -EvidenceDirectory "$p/ranges" *> "$p/ranges.log"
"ranges exit=$LASTEXITCODE" | Add-Content "$p/ranges-queue.txt"

