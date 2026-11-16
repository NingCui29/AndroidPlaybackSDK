try { [Console]::OutputEncoding=[System.Text.Encoding]::UTF8 } catch {}
$prev=Get-Process -Id 16388 -ErrorAction Stop
$d='artifacts/validation/20261113-144238-timings/progress/soft-output'
$s='10.67.116.12:5555'; $apk="$d/apk/test.apk"
"Waiting for PCM and geometry runner $($prev.Id), started $($prev.StartTime.ToString('o')); then surface 10 rounds and current-candidate format matrix." | Set-Content "$d/lifecycle-queue.txt"
Wait-Process -InputObject $prev
if(!((Get-Content "$d/device-queue.txt") -match '^geometry (PASS|ERROR)')){throw 'Previous device runner has no terminal marker'}
$env:ANDROID_HOME=Join-Path $env:LOCALAPPDATA Android/Sdk
try { & ./tools/run-device-tests.ps1 -Serial $s -Suite surface -Rounds 10 -SkipBuild -ApkPath $apk -EvidenceDirectory "$d/surface" *> "$d/surface.log"; 'surface PASS' | Add-Content "$d/lifecycle-queue.txt" }
catch { "surface ERROR $($_.Exception.Message)" | Add-Content "$d/lifecycle-queue.txt" }
& ./tools/run-format-matrix.ps1 -Serial $s -ApkPath $apk *> "$d/matrix.log"
"matrix terminal; inspect verified.json for gates" | Add-Content "$d/lifecycle-queue.txt"

