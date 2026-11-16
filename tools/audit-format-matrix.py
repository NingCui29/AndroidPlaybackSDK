"""Cross-check the D01 matrix plan against individual instrumentation results."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re

parser = argparse.ArgumentParser()
parser.add_argument("matrix", type=Path)
matrix = parser.parse_args().matrix.resolve()
root = Path(__file__).resolve().parents[1]
plan = Counter(tuple(row.split()) for row in (matrix / "plan.txt").read_text().splitlines())
actual = Counter()
groups = {}
firmware = set()
apk_hash = hashlib.file_digest((matrix / "test.apk").open("rb"), "sha256").hexdigest()
for group in (matrix / "runs.txt").read_text().splitlines():
    kind, policy, label, relative = group.split(maxsplit=3)
    if label != "Evidence:":
        raise SystemExit(group)
    evidence = root / relative.replace("\\", "/")
    device = (evidence / "device.txt").read_text().splitlines()
    if device[0].split()[-1].lower() != apk_hash:
        raise SystemExit(f"Test APK mismatch in {evidence}")
    firmware.add(tuple(device[1:]))
    staged = {name: digest for digest, name in
              (row.split() for row in (evidence / "fixtures.sha256").read_text().splitlines())}
    result = (evidence / "instrumentation.txt").read_text().splitlines()
    if not result or result[-1] != "INSTRUMENTATION_CODE: -1":
        raise SystemExit(f"Suite failed in {evidence}")
    rows = 0
    for row in result[:-1]:
        match = re.fullmatch(r"INSTRUMENTATION_RESULT: ([^. ]+\.\w+)=PASS (\w+) (\w+) video=(\S*) finalVideo=(\d+) finalPCM=(\d+)", row)
        if not match:
            raise SystemExit(f"Unexpected case result in {evidence}: {row}")
        name, input_kind, mode, backend, pictures, pcm = match.groups()
        if (input_kind, mode) != (kind, policy) or name not in staged:
            raise SystemExit(f"Case identity mismatch: {row}")
        if ((name.startswith("V") != (int(pictures) > 0)) or ((name != "V11.mp4") != (int(pcm) > 0))
                or (mode == "HARDWARE_ONLY" and backend in ("", "ffmpeg"))):
            raise SystemExit(f"Output mismatch: {row}")
        actual[(kind, policy, name)] += 1
        rows += 1
    groups[f"{kind}/{policy}"] = rows
if plan != actual or any(n != 1 for n in plan.values()) or len(firmware) != 1 or not actual:
    raise SystemExit(f"Missing={plan-actual} unexpected={actual-plan} device profiles={len(firmware)}")
print(json.dumps({"passed": sum(actual.values()), "groups": groups, "testApkSHA256": apk_hash,
                  "device": list(firmware.pop())}, indent=2))
