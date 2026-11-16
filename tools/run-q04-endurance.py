"""Four independent, resumable 2-hour Q04 measurements on one explicitly selected device."""

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

from q04_capture import file_digest
from summarize_q04_io import text_file

ROOT = Path(__file__).resolve().parents[1]
RAW = ("samples.csv", "events.csv", "rendered.csv", "inventory.tsv")
PACKAGE = "com.cn.media.playback.test"
JOBS = (("q04V01", 1), ("q04V01", 2), ("q04A03", 1), ("q04A03", 2))


def now():
    return datetime.now(timezone.utc).isoformat()


def write(path, data):
    tmp = path.with_suffix(".writing")
    tmp.write_text(json.dumps(data, indent=2, ensure_ascii=False)+"\n", encoding="utf-8")
    tmp.replace(path)


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


@contextmanager
def locked(path):
    # OS locks disappear on process exit. The persistent file does not indicate a live process.
    with path.open("a+b") as f:
        if not f.tell(): f.write(b"0"); f.flush()
        f.seek(0)
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(f.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(f.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        try: yield
        finally:
            f.seek(0)
            if os.name == "nt": msvcrt.locking(f.fileno(), msvcrt.LK_UNLCK, 1)
            else: fcntl.flock(f.fileno(), fcntl.LOCK_UN)


def prepare(campaign, apk, serial):
    pinned = campaign/"test.apk"
    sample = {"q04V01": ROOT/"testdata/baseline/V01.mp4", "q04A03": ROOT/"testdata/formats/A03.flac"}
    plan = {"serial": serial, "apkSha256": file_digest(apk), "policy": "AUTO",
            "steps": [{"suite": suite, "round": n, "effectiveMediaMs": 7200000,
                       "cycles": 12 if suite == "q04V01" else 900,
                       "sample": sample[suite].name, "sampleSha256": file_digest(sample[suite])} for suite,n in JOBS]}
    if (campaign/"plan.json").exists():
        if load(campaign/"plan.json") != plan or file_digest(pinned) != plan["apkSha256"]:
            raise ValueError("Campaign already pins different input, device, or APK")
    else:
        if pinned.exists() and file_digest(pinned) != plan["apkSha256"]: raise ValueError("Unrecognized APK")
        if not pinned.exists(): shutil.copyfile(apk, pinned)
        if file_digest(pinned) != plan["apkSha256"]: raise ValueError("APK changed while pinning")
        write(campaign/"plan.json", plan)
    return plan


class Device:
    def __init__(self, adb, serial):
        self.prefix = [str(adb), "-s", serial]

    def call(self, *cmd, timeout=45):
        try:
            r = subprocess.run([*self.prefix, *cmd], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        except subprocess.TimeoutExpired:
            return None
        return r.stdout if r.returncode == 0 else None

    def instrumentation(self):
        dump = self.call("shell", "dumpsys", "activity", "processes")
        if dump is None: return None
        s = dump.decode(errors="replace")
        if "Permission Denial" in s or "Unknown command" in s or "Bad activity command" in s: return None
        # ActiveInstrumentation is the runtime record for an actual instrumentation, not a process listing.
        if re.search(r"ActiveInstrumentation\{", s):
            return True
        if re.search(r"ACTIVITY MANAGER[^\n]*PROCESS", s.upper()): return False
        return None

    def append_raw(self, evidence, partial):
        log = evidence/"instrumentation.txt"
        if not log.is_file(): return None
        m = re.search(r"(?m)^INSTRUMENTATION_(?:STATUS|RESULT): trace=(q04-[0-9]+)\s*$", text_file(log))
        if not m: return None
        trace = m.group(1)
        partial.mkdir(exist_ok=True)
        for n in RAW:
            target = partial/n
            offset = target.stat().st_size if target.exists() else 0
            # exec-out preserves LF on Windows but can return success on remote read errors.
            command = f"run-as {PACKAGE} tail -c +{offset+1} cache/{trace}/{n} 2>/dev/null && printf Q04_READ_OK"
            payload = self.call("exec-out", command)
            if payload is None or not payload.endswith(b"Q04_READ_OK"):
                continue
            chunk = payload[:-len(b"Q04_READ_OK")]
            if not offset and chunk and not chunk.startswith((b"group,",b"mark\t")):
                # Some ADB versions pass diagnostics through stdout.
                continue
            with target.open("ab") as f:
                f.write(chunk)
                f.flush()
        return trace


def host_alive(pid, evidence, shell):
    if not pid: return False
    if os.name != "nt":
        try:
            args = (Path("/proc")/str(pid)/"cmdline").read_bytes().decode(errors="replace")
            return str(evidence) in args and "run-device-tests.ps1" in args
        except FileNotFoundError: return False
        except PermissionError: return None
    # Match the expected command line as well as PID, since Windows reuses process IDs.
    cmd = f"$p=Get-CimInstance Win32_Process -Filter 'ProcessId={int(pid)}' -ErrorAction Stop; if($p){{$p.CommandLine}}"
    r = subprocess.run([shell, "-NoProfile", "-Command", cmd], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if r.returncode: return None
    args = r.stdout.decode(errors="replace").lower()
    return str(evidence).lower() in args and "run-device-tests.ps1" in args


def inspect_completed(evidence, step, plan):
    report = evidence/"summary.json"
    if not report.is_file(): return None
    report = load(report)
    if report["plan"]["suite"] != step["suite"] or report["plan"]["policy"] != plan["policy"] or report["plan"]["serial"] != plan["serial"]:
        raise ValueError("Unexpected device suite or policy in archived result")
    if int(report["plan"]["rounds"]) != 1 or report["device"][0].split()[-1].lower() != plan["apkSha256"]:
        raise ValueError("Unexpected APK/round count")
    if report["fixtures"] != [step["sampleSha256"]+"  "+step["sample"]]:
        raise ValueError("Unexpected fixture evidence")
    for name, digest in report["sha256"].items():
        if file_digest(evidence/name) != digest: raise ValueError("Evidence changed after scoring: " + name)
    r = report["rounds"].get("1", {})
    complete = r.get("completedCycles") == step["cycles"] and r.get("mediaMs") == step["effectiveMediaMs"]
    if not complete: status = "partial"
    elif report["failures"]: status = "measured-failure"
    else: status = "completed"
    return {"status": status, "firmware": report["device"][1:], "cycles": r.get("completedCycles",0),
            "wallMs": r.get("wallMs"), "individualPending": report["pending"], "failures": report["failures"],
            "summarySha256": file_digest(evidence/"summary.json")}


def run_attempt(attempt, step, plan, campaign, device, shell):
    evidence, meta = attempt/"evidence", attempt/"launch.json"
    proc = None
    if not meta.exists():
        cmd = [shell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(ROOT/"tools/run-device-tests.ps1"),
               "-Serial", plan["serial"], "-Suite", step["suite"], "-Policy", plan["policy"], "-Rounds", "1",
               "-SkipBuild", "-ApkPath", str(campaign/"test.apk"), "-EvidenceDirectory", str(evidence)]
        flags = (subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW) if os.name == "nt" else 0
        # The measurement child may outlive this orchestration process. Reattach by recorded identity on restart.
        with (attempt/"runner.log").open("ab") as log:
            proc = subprocess.Popen(cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                                    creationflags=flags, start_new_session=os.name != "nt")
        write(meta, {"pid": proc.pid, "startUtc": now(), "suite": step["suite"], "round": step["round"]})
    pid = load(meta)["pid"]
    while True:
        trace = device.append_raw(evidence, attempt/"partial")
        active = (proc.poll() is None) if proc else host_alive(pid, evidence, shell)
        if active is False:
            score = inspect_completed(evidence, step, plan)
            if score: break
            instrument = device.instrumentation()
            if instrument is False:
                score = {"status":"partial", "reason":"runner exited and device instrumentation inactive; complete scoring evidence unavailable"}
                break
        write(attempt/"progress.json", {"observedUtc": now(), "hostPid": pid, "runnerLive": active,
                                       "deviceTrace": trace, "snapshots": {
                                           name: path.stat().st_size for name in RAW
                                           if (path := attempt/"partial"/name).is_file()}})
        time.sleep(45)
    score.update(finishedUtc=now(), attempt=attempt.name)
    write(attempt/"outcome.json", score)
    return score


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--campaign", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--after", type=Path, help="Start only after this preceding device-run directory has a terminal result")
    parser.add_argument("--retry-partial", action="store_true", help="Rerun each incomplete 2h round from its start, preserving old attempts")
    opts = parser.parse_args()
    campaign = opts.campaign.resolve()
    campaign.mkdir(parents=True, exist_ok=True)
    with locked(campaign/".manager.lock"):
        plan = prepare(campaign, opts.apk, opts.serial)
        if opts.prepare_only:
            print(campaign/"plan.json")
            return 0
        if opts.after:
            if not opts.after.is_dir(): raise ValueError("Preceding device-run directory does not exist")
            while True:
                path = opts.after/"instrumentation.txt"
                if path.is_file() and "INSTRUMENTATION_CODE:" in text_file(path):
                    break
                time.sleep(45)
        sdk = os.getenv("ANDROID_HOME") or os.getenv("ANDROID_SDK_ROOT")
        adb = shutil.which("adb") or str(Path(sdk or Path(os.environ["LOCALAPPDATA"])/"Android/Sdk")/"platform-tools"/("adb.exe" if os.name == "nt" else "adb"))
        device = Device(adb, opts.serial)
        shell = shutil.which("pwsh") or shutil.which("powershell")
        if not shell: raise ValueError("PowerShell is required for the device runner")
        outcomes = {}
        for step in plan["steps"]:
            key = step["suite"] + "-R" + str(step["round"])
            target = campaign/key
            target.mkdir(exist_ok=True)
            attempts = sorted(target.glob("attempt-*"))
            prior = attempts[-1] if attempts else None
            done = prior/"outcome.json" if prior else None
            if done and done.exists():
                score = load(done)
                if score["status"] != "partial":
                    checked = inspect_completed(prior/"evidence", step, plan)
                    if checked is None or checked["summarySha256"] != score["summarySha256"]:
                        raise ValueError("Completed evidence was changed: " + key)
                if score["status"] != "partial" or not opts.retry_partial:
                    outcomes[key] = score
                    continue
                prior = None
            if not prior:
                while device.instrumentation() is not False: time.sleep(45)
                prior = target / f"attempt-{len(attempts)+1:03d}"
                prior.mkdir()
            outcomes[key] = run_attempt(prior, step, plan, campaign, device, shell)
            write(campaign/"rounds.json", outcomes)
        complete = all(r["status"] == "completed" for r in outcomes.values())
        firmware = {tuple(r["firmware"]) for r in outcomes.values() if r.get("firmware")}
        write(campaign/"rounds.json", outcomes)
        status = {"plannedRounds":4, "completedRounds":sum(r["status"] == "completed" for r in outcomes.values()),
                  "internalLongRunVerified":complete and len(firmware) == 1,
                  "externalEvidence":"Score acoustic/optical captures separately; CPU regression requires a paired reference"}
        write(campaign/"summary.json", status)
        print(campaign/"summary.json")
        return 0 if status["internalLongRunVerified"] else 1


if __name__ == "__main__":
    sys.exit(main())
