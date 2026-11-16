"""Score externally measured Q04 light/audio markers and panel frame changes."""

from collections import defaultdict
from hashlib import sha256
import csv
import json
import math
from pathlib import Path


def file_digest(path):
    h = sha256()
    with path.open("rb") as reader:
        for chunk in iter(lambda: reader.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def evaluate(path, suite, rounds, apk_hash, fixture_hash):
    spec = json.loads(path.read_text(encoding="utf-8-sig"))
    if not spec.get("clock") or not spec.get("method"):
        raise ValueError("Identify physical capture method and common clock")
    if spec["apkSha256"].lower() != apk_hash.lower() or spec["fixtureSha256"].lower() != fixture_hash.lower():
        raise ValueError("Physical capture provenance does not match this APK and fixture")
    sources = spec["recordings"]
    if not sources or not all(file_digest(path.parent / n) == s.lower() for n, s in sources.items()):
        raise ValueError("Missing recording or checksum mismatch")
    data_file = path.parent / spec["measurements"]
    if file_digest(data_file) != spec["measurementsSha256"].lower():
        raise ValueError("Measurement CSV checksum mismatch")
    measured = defaultdict(list)
    with data_file.open(encoding="utf-8-sig", newline="") as src:
        for r in csv.DictReader(src):
            num = int(r["round"])
            if r["recording"] not in sources or not 1 <= num <= rounds:
                raise ValueError("Measurement references unknown round/recording")
            measured[num].append(r)
    failures, out = [], {}
    if suite in {"q04V01", "q01V01"}:
        residual, bias = float(spec["calibrationResidualMs"]), float(spec["calibrationOffsetMs"])
        calibration = spec["calibrationRecording"]
        if calibration not in sources or not (0 <= residual <= 10) or not math.isfinite(bias):
            raise ValueError("Calibration missing or outside the 10 ms residual limit")
        if suite == "q04V01":
            positions = {"begin": range(1, 11), "middle": range(3601, 3611), "end": range(7190, 7200)}
        else:
            # Resume and seek points depend on the actual playback trace.
            positions = dict.fromkeys(("start", "steady", "resume", "seek"))
        for n in range(1, rounds+1):
            out[n] = {}
            for phase, seconds in positions.items():
                group = [r for r in measured[n] if r["phase"] == phase]
                markers = [int(r["mediaMs"]) for r in group]
                valid = (set(markers) == {s*1000 for s in seconds} if seconds is not None else
                         len(set(markers)) == 10 and all(1000 <= v <= 599000 and v % 1000 == 0 for v in markers))
                if len(group) != 10 or not valid:
                    failures.append(f"physical R{n}/{phase}: missing or duplicate markers")
                    continue
                offsets = [float(r["toneMs"])-float(r["flashMs"])-bias for r in group]
                if not all(math.isfinite(d) for d in offsets): raise ValueError("Non-finite marker offset")
                absolute = sorted(abs(d) for d in offsets)
                p95 = absolute[math.ceil(len(absolute)*0.95)-1]
                out[n][phase] = {"offsetsMs": offsets, "absoluteP95Ms": p95, "absoluteMaxMs": absolute[-1]}
                if p95 > 80 or absolute[-1] > 150: failures.append(f"physical R{n}/{phase}: K04 limit")
            expected = 10 * len(positions)
            if len(measured[n]) != expected: failures.append(f"physical R{n}: expected exactly {expected} marker rows")
    elif suite == "q04Realtime":
        for n in range(1, rounds+1):
            window = spec["roundWindows"][str(n)]
            start, end = int(window["startNs"]), int(window["endNs"])
            shown = [(int(r["visibleNs"]), int(r["frameNumber"])) for r in measured[n]]
            if not shown or not 0.99 <= 600e9 / (end-start) <= 1.01:
                failures.append(f"physical R{n}: incomplete 10-minute capture")
                continue
            if any(not start <= t <= end or not 0 <= frame < 18000 for t,frame in shown):
                raise ValueError("Out-of-window frame observation")
            if any(t1 <= t0 or f1 <= f0 for (t0,f0),(t1,f1) in zip(shown,shown[1:])):
                raise ValueError("Expected one row per newly visible source frame, increasing in time and frame number")
            sequence = [start, *(t for t,_ in shown), end]
            max_gap = max(b-a for a,b in zip(sequence,sequence[1:])) / 1e6
            missing = (18000-len(shown)) / 18000
            out[n] = {"distinctVisibleFrames": len(shown), "missingFraction": missing, "maxGapMs": max_gap}
            if max_gap > 200 or missing > .01: failures.append(f"physical R{n}: K05 limit")
    else:
        raise ValueError("Unknown physical capture suite: " + suite)
    return {"captureManifestSha256": file_digest(path), "recordings": sources, "rounds": out}, failures
