try { [Console]::OutputEncoding=[System.Text.Encoding]::UTF8 } catch {}
$prior=Get-Process -Id 21700
$p='artifacts/validation/20261113-144238-timings/progress/soft-output'
$apk="$p/apk/test.apk"
$expected='561B5ABC5A5E1846FF21F5B594C0753C8969665F89E1BD384E6C1B2C2F475D3E'
"L01 D01 audioTracks and audioRecovery x10 each after content PID $($prior.Id); physical routing requires separate evidence; apk $expected" | Set-Content "$p/audio-queue.txt"
$prior.WaitForExit()
if ((Get-Content "$p/content-queue.txt" -Raw) -notmatch 'content exit=') {throw 'No terminal content result'}
foreach($suite in 'audioTracks','audioRecovery'){
 if ((Get-FileHash $apk).Hash -ne $expected) {throw 'Candidate APK changed'}
 & pwsh -NoProfile -File tools/run-device-tests.ps1 -Serial 10.67.116.12:5555 -SkipBuild -ApkPath $apk -Suite $suite -Rounds 10 -EvidenceDirectory "$p/$suite" *> "$p/$suite.log"
 "$suite exit=$LASTEXITCODE" | Add-Content "$p/audio-queue.txt"
}

