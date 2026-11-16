"""K01-K03 on D01. Cold means a new host process, not a cleared OS page cache."""
import argparse
import csv
from datetime import datetime
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("--serial", required=True)
parser.add_argument("--apk", type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
os.chdir(root)
sdk = Path(os.environ["ANDROID_HOME"])
adb = sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
host = "com.cn.media.playback.test"
component = host + "/com.cn.media.playback.InputChecks"
out = root / "artifacts/validation" / datetime.now().strftime("%Y%m%d-%H%M%S-timings")
out.mkdir()
pinned = out / "test.apk"
shutil.copyfile(args.apk, pinned)

def dev(*command):
    return subprocess.check_output([str(adb), "-s", args.serial, *map(str, command)],
                                   encoding="utf-8", errors="replace").strip()

def digest(path):
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()

# A queued intermediate build can be superseded before it acquires the board.
skip_record = args.apk.with_name("superseded-timings.json")
if skip_record.is_file():
    skip = json.loads(skip_record.read_text(encoding="utf-8"))
    if skip["apkSha256"] == digest(pinned):
        (out / "summary.json").write_text(json.dumps({"status": "superseded", **skip}, indent=2))
        print(f"Skipped superseded timing run: {out}")
        raise SystemExit(0)

if re.search(r"mClass=ComponentInfo\{" + re.escape(host) + r"/[^}]+\}\s+mFinished=false",
             dev("shell", "dumpsys", "activity", "processes")):
    raise SystemExit("Device is running a playback test; retry after the current instrumentation finishes")
if "Success" not in dev("install", "-r", "-t", pinned):
    raise SystemExit("Timing APK installation failed")
device = {key: dev("shell", "getprop", key) for key in ["ro.build.fingerprint", "ro.product.cpu.abi", "ro.build.version.sdk"]}
fixtures = {}
for name in ["V01.mp4", "V11.mp4", "A02.m4a", "A04.wav"]:
    file = root / "testdata/baseline" / name
    fixtures[name] = digest(file)
    got = subprocess.run([str(adb), "-s", args.serial, "shell", "run-as", host, "sha256sum", "cache/"+name],
                         capture_output=True, text=True).stdout
    if got.split()[:1] != [fixtures[name]]:
        stage = "/data/local/tmp/playback-" + fixtures[name][:20]
        try:
            dev("push", file, stage)
            dev("shell", "run-as", host, "cp", stage, "cache/" + name)
        finally:
            dev("shell", "rm", "-f", stage)
        if dev("shell", "run-as", host, "sha256sum", "cache/"+name).split()[0] != fixtures[name]:
            raise SystemExit("Timing fixture verification failed: " + name)
(out / "plan.json").write_text(json.dumps({"device":device,"testAPK":digest(pinned),"fixtures":fixtures,
    "mode":"SOFTWARE_ONLY", "startup":"V01/A02/A04 cold/warm 30 each",
    "seek":"V01/V11/A02 forward/backward 15 each"}, indent=2))
def timing_stats(name, group, values):
    values = sorted(values)
    limit = (1000 if group == "cold" else 600) if name == "V01.mp4" else (800 if group == "cold" else 500)
    expected = 30
    if group in ("forward", "backward"): limit, expected = 800, 15
    p95 = values[math.ceil(.95 * len(values))-1]
    return {"n":len(values),"p50":values[math.ceil(.50 * len(values))-1],
            "p95":p95,"max":values[-1],"limit":limit,
            "overLimitCount":sum(value > limit for value in values),"pass":p95 <= limit and len(values)==expected}

series = {}
failures = []
with (out / "samples.csv").open("w", newline="") as report:
    table = csv.writer(report)
    table.writerow(["input", "condition", "run", "latencyMs", "targetMs", "actualMs", "signedErrorMs"])
    for operation, names in [("seek", ["V01.mp4"]),
                             ("cold", ["V01.mp4","A02.m4a","A04.wav"]),
                             ("warm", ["V01.mp4","A02.m4a","A04.wav"]),
                             ("seek", ["V11.mp4","A02.m4a"])]:
        for name in names:
            repetitions = 30 if operation == "cold" else 1
            for run in range(repetitions):
                dev("shell","am","force-stop",host)
                rounds = 1 if operation == "cold" else 30
                output = dev("shell","am","instrument","-w","-e","suite","timing","-e","samples",name,
                             "-e","operation",operation,"-e","rounds",str(rounds),component)
                log = f"{name}-{operation}-{run}.txt"
                (out/log).write_text(output + "\n", encoding="utf-8")
                rows = re.findall(r"(?m)^INSTRUMENTATION_RESULT: sample-(\d+)=(\w+),([\d.]+),(\d+),(\d+),(-?\d+)$", output)
                if "INSTRUMENTATION_CODE: -1" not in output or len(rows) != rounds:
                    failures.append(log)
                for index, group, latency, target, actual, error in rows:
                    table.writerow([name, group, run if operation == "cold" else index, latency, target, actual, error])
                    series.setdefault((name,group), []).append(float(latency))
                report.flush()
                if operation == "seek" and name == "V01.mp4":
                    groups = {direction: timing_stats(name, direction, series[(name, direction)])
                              for direction in ("forward", "backward") if (name, direction) in series}
                    passed = len(groups) == 2 and not failures and all(g["pass"] for g in groups.values())
                    early = {"testAPK": digest(pinned), "sample": name, "groups": groups, "pass": passed}
                    (out / "v01-seek.json").write_text(json.dumps(early, indent=2), encoding="utf-8")

summary = {name + "/" + group: timing_stats(name, group, values)
           for (name, group), values in series.items()}
(out/"summary.json").write_text(json.dumps({"groups":summary,"failedRuns":failures}, indent=2))
print(out)
if failures or len(summary) != 12 or not all(s["pass"] for s in summary.values()):
    raise SystemExit("Incomplete timing or exceeded gate")
