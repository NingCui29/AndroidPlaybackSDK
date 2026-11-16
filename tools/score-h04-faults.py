"""Score the actual first software output after an injected hardware failure."""
import argparse
import json
import math
from pathlib import Path
import re

STAGES = ("create", "configure", "start", "runtime", "pipeRuntime", "pipeNextEntry", "pipeNoEntry")


def score(lines, rounds):
    if "INSTRUMENTATION_CODE: -1" not in lines or rounds < 1:
        raise ValueError("No successful suite or invalid repetition count")
    values = {}
    for line in lines:
        m = re.fullmatch(r"INSTRUMENTATION_RESULT: (\d+)-(AUTO|HARDWARE_ONLY)-(\w+?)"
                         r"(PositionErrorUs|RecoveryUs|AudioAdvance|Ms)=(-?\d+)", line)
        if m:
            n, mode, stage, metric, value = m.groups()
            key = (int(n), mode, stage, metric)
            if key in values:
                raise ValueError("Duplicate H04 measurement")
            values[key] = int(value)
    samples = []
    for n in range(rounds):
        for stage in STAGES:
            for mode in ("AUTO", "HARDWARE_ONLY"):
                def consume(metric):
                    key = (n, mode, stage, metric)
                    if key not in values:
                        raise ValueError(f"Missing H04 measurement {key}")
                    return values.pop(key)
                row = {"round": n, "stage": stage, "mode": mode, "observationMs": consume("Ms")}
                if mode == "AUTO" and stage == "pipeNoEntry":
                    row["audioAdvance"] = consume("AudioAdvance")
                    if row["audioAdvance"] <= 0:
                        raise ValueError("No audio consumption after sequential video loss")
                elif mode == "AUTO":
                    row["firstSoftwareUs"] = consume("RecoveryUs")
                    row["positionErrorUs"] = consume("PositionErrorUs")
                    if row["firstSoftwareUs"] < 0:
                        raise ValueError("Recovery output predates fault")
                samples.append(row)
    if values:
        raise ValueError("Unexpected H04 measurements")
    seekable = [s for s in samples if s["mode"] == "AUTO" and s["stage"] == "runtime"]
    times = sorted(s["firstSoftwareUs"] for s in seekable)
    p95 = times[math.ceil(.95*len(times))-1]
    error = max(abs(s["positionErrorUs"]) for s in seekable)
    passed = p95 <= 1500000 and times[-1] <= 4000000 and error <= 33334
    return {"cases": len(samples), "rounds": rounds, "samples": samples, "seekableRecoveryP95Us": p95,
            "seekableRecoveryMaxUs": times[-1], "maxPositionErrorUs": error,
            "status": "FAIL" if not passed else "PASS" if rounds >= 10 else "pilot"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path)
    parser.add_argument("--rounds", type=int, required=True)
    args = parser.parse_args()
    report = score((args.run/"instrumentation.txt").read_text(encoding="utf-8-sig").splitlines(), args.rounds)
    report["installedApk"] = json.loads((args.run/"installed-apk.json").read_text(encoding="utf-8-sig"))
    report["device"] = (args.run/"device.txt").read_text(encoding="utf-8-sig").splitlines()
    report["fixtures"] = (args.run/"fixtures.sha256").read_text(encoding="utf-8-sig").splitlines()
    (args.run/"h04.json").write_text(json.dumps(report, indent=2)+"\n", encoding="utf-8")
    if report["status"] == "FAIL":
        raise SystemExit("H04 seekable runtime recovery exceeded K10")


if __name__ == "__main__":
    main()
