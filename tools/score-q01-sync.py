"""Score physical K04 measurements against the installed test APK and the staged V01 fixture."""
import argparse
import json
from pathlib import Path
import re

from q04_capture import evaluate, file_digest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", required=True, type=Path, help="Completed D01 q01 run evidence directory")
    parser.add_argument("--capture", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    run = args.run
    trace = (run/"instrumentation.txt").read_text(encoding="utf-8-sig")
    completed = re.findall(r"^INSTRUMENTATION_RESULT: V01\.mp4-(\d+)=PASS frames=\d+ dropped=\d+ pcm=28800000 endEvents=1 seekCount=\d+\s*$", trace, re.M)
    if not completed or not re.search(r"^INSTRUMENTATION_CODE: -1\s*$", trace, re.M):
        raise ValueError("Missing successful Q01 V01 run evidence")
    fixtures = [s.split() for s in (run/"fixtures.sha256").read_text(encoding="utf-8-sig").splitlines()]
    digest, = [row[0] for row in fixtures if len(row) == 2 and row[1] == "V01.mp4"]
    installed = json.loads((run/"installed-apk.json").read_text(encoding="utf-8-sig"))
    capture = json.loads(args.capture.read_text(encoding="utf-8-sig"))
    if (capture.get("instrumentationSha256") != file_digest(run/"instrumentation.txt") or
            str(capture.get("playbackRound")) not in completed):
        raise ValueError("Capture and Q01 run are not associated")
    report, failures = evaluate(args.capture, "q01V01", 1, installed["apkSha256"], digest)
    report.update(status="FAIL" if failures else "PASS", failures=failures, apkSha256=installed["apkSha256"],
                  fixtureSha256=digest, instrumentationSha256=capture["instrumentationSha256"],
                  playbackRound=capture["playbackRound"])
    args.output.write_text(json.dumps(report, indent=2)+"\n", encoding="utf-8")
    if failures:
        raise SystemExit("K04 measurements outside limits or incomplete: " + str(args.output))


if __name__ == "__main__":
    main()
