try { [Console]::OutputEncoding=[System.Text.Encoding]::UTF8 } catch {}
$next='artifacts/validation/20261113-144238-timings/progress/soft-output'
(Get-Process -Id 19132).WaitForExit()
if ((Get-Content "$next/quality-queue.txt" -Raw) -notmatch 'q04Release exit=') {throw 'Preceding quality runner did not reach release suite'}
python tools/run-q04-endurance.py --campaign "$next/endurance" --serial 10.67.116.12:5555 --apk "$next/apk/test.apk" *> "$next/endurance-run.log"
exit $LASTEXITCODE

