try { [Console]::OutputEncoding=[System.Text.Encoding]::UTF8 } catch {}
$parentRun=Get-Process -Id 36880
$rootRun='artifacts/validation/20261113-144238-timings/progress/soft-output'
$apkRun=Get-Item "$rootRun/apk/test.apk"
$digestRun=(Get-FileHash $apkRun).Hash
"waiting for lifecycle runner PID $($parentRun.Id); apk $digestRun; q03 x10, q04Realtime x3, q04Release x50" | Set-Content "$rootRun/quality-queue.txt"
$parentRun.WaitForExit()
if ((Get-Content "$rootRun/lifecycle-queue.txt" -Raw) -notmatch 'matrix terminal;') {throw 'Preceding matrix did not record completion'}
foreach ($suiteRun in 'q03','q04Realtime','q04Release') {
 $roundsRun=@{q03=10;q04Realtime=3;q04Release=50}[$suiteRun]
 if ((Get-FileHash $apkRun).Hash -ne $digestRun) {throw 'Pinned APK changed'}
 & pwsh -NoProfile -File tools/run-device-tests.ps1 -SkipBuild -Serial 10.67.116.12:5555 -Suite $suiteRun -Rounds $roundsRun -ApkPath $apkRun.FullName -EvidenceDirectory "$rootRun/$suiteRun" *> "$rootRun/$suiteRun-run.log"
 "$suiteRun exit=$LASTEXITCODE" | Add-Content "$rootRun/quality-queue.txt"
}

