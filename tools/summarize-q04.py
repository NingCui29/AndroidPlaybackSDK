"""Summarize Q04 device CSVs. q04Release exits 0 when measured, 1 on failure, 2 for pending evidence."""

import argparse
from collections import defaultdict
import csv
from hashlib import sha256
import io
import json
import math
from pathlib import Path
from statistics import median
from tempfile import NamedTemporaryFile
from q04_capture import evaluate
from summarize_q04_io import text_file as read


def memory_deltas(before, after, keys):
    result = {}
    for key in keys:
        old = [int(sample[key]) for sample in before if sample.get(key)]
        new = [int(sample[key]) for sample in after if sample.get(key)]
        if old and new:
            divisor = 1048576 if key.endswith("Bytes") else 1024
            result[key] = (median(new) - median(old)) / divisor
    return result


def rows(path):
    return list(csv.DictReader(io.StringIO(read(path))))


def rendered_by_round(path, realtime):
    # A complete pair of 2-hour V01 runs has up to 432,000 callback records.
    with path.open("rb") as source:
        prefix = source.read(2)
    enc = "utf-16" if prefix in (b"\xff\xfe", b"\xfe\xff") else "utf-8-sig"
    result = defaultdict(list if realtime else int)
    with path.open(encoding=enc, newline="") as source:
        for row in csv.DictReader(source):
            key = (row["group"], int(row["round"]))
            if realtime:
                result[key].append((int(row["renderNs"]), int(row["ptsUs"])))
            else:
                result[key] += 1
    return result


def p95(values):
    values = sorted(values)
    return values[math.ceil(0.95 * len(values))-1] if values else None


def summary(values):
    return dict(count=len(values), median=median(values) if values else None,
                p95=p95(values), max=max(values) if values else None)


def by_round(items):
    groups = defaultdict(list)
    for r in items:
        groups[(r["group"], int(r["round"]))].append(r)
    return groups


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--reference", type=Path, help="Previous Q04 summary.json, same conditions")
    parser.add_argument("--capture", type=Path, help="External capture manifest including raw recording hashes")
    args = parser.parse_args()
    d = args.directory
    plan = dict(line.split("=", 1) for line in read(d / "plan.txt").splitlines() if "=" in line)
    suite = plan["suite"]
    target_rounds = {"q04Pilot": 1, "q04Realtime": 3, "q04V01": 2, "q04A03": 2, "q04Release": 50}[suite]
    requested_rounds = int(plan["rounds"])
    samples = rows(d / "samples.csv")
    events = rows(d / "events.csv")
    frames = rendered_by_round(d / "rendered.csv", suite == "q04Realtime")
    failures, pending = [], []
    def check(condition, why):
        if not condition:
            failures.append(why)
    def reasons_missing():
        if requested_rounds != target_rounds: pending.append(f"planned {requested_rounds} rounds; required {target_rounds}")
        if suite == "q04Pilot": pending.append("short A03 pilot")
        if suite == "q04Realtime" and plan["policy"] != "AUTO": pending.append("backend comparison; primary AUTO required")
    reasons_missing()
    report = {"plan": plan, "expectedRounds": target_rounds, "device": read(d / "device.txt").splitlines(),
              "fixtures": read(d / "fixtures.sha256").splitlines(), "rounds": {}, "pending": pending, "failures": failures}
    check("INSTRUMENTATION_CODE: -1" in read(d / "instrumentation.txt"), "instrumentation did not complete")
    check(any(e["event"] == "suiteFinished" for e in events), "no final suite event")
    failures.extend(e["detail"] for e in events if e["event"] == "failure")
    check(bool(samples), "empty sample trace")
    ticks = [r for r in samples if r["kind"] == "tick"]
    gaps = [int(b["monoMs"])-int(a["monoMs"]) for a, b in zip(ticks, ticks[1:])]
    report["samplingGapMs"] = summary(gaps)
    check(len(gaps) > 0 and all(0 < t <= 2000 for t in gaps), "missing, nonmonotonic or interrupted 1 Hz telemetry")
    check(all(int(r["pssKiB"]) > 0 and int(r["nativeBytes"]) > 0 for r in samples), "invalid memory samples")

    if suite == "q04Release":
        if ticks:
            check(int(ticks[0]["monoMs"]) - int(samples[0]["monoMs"]) <= 2000 and
                  int(samples[-1]["monoMs"]) - int(ticks[-1]["monoMs"]) <= 2000,
                  "1 Hz telemetry does not cover the release run")
        baseline = [r for r in samples if r["kind"] == "baseline"]
        check(len(baseline) == 10, "missing warm idle baseline")
        counters = ("fixtureOrPipeFd", "nativeInputFd", "sdkNamedThreads", "liveCodecs")
        base = {key: median([int(r[key]) for r in baseline]) for key in
                (*counters, "pssKiB", "nativeBytes", "processFd", "processThreads")} if baseline else {}
        report["baseline"] = base
        memory_keys = ("pssKiB", "nativeBytes", "javaUsedBytes", "pss-java-heap-KiB",
                       "pss-native-heap-KiB", "pss-code-KiB", "pss-stack-KiB",
                       "pss-graphics-KiB", "pss-private-other-KiB", "pss-system-KiB")
        release_groups = ("normalStop", "normalRelease", "stalledStop", "stalledRelease")
        before_warmup = [r for r in samples if r["kind"] == "coldIdle"]
        resource_reference = base
        if not before_warmup:
            pending.append("missing pre-warmup idle resource sample")
        elif len(before_warmup) != 1:
            failures.append("duplicate pre-warmup idle resource samples")
        else:
            resource_reference = {key: int(before_warmup[0][key]) for key in counters}
            report["coldIdleResources"] = resource_reference
            report["coldToWarmIdleMiB"] = memory_deltas(before_warmup, baseline, memory_keys)
            warmup_groups = {"warmup" + group[0].upper() + group[1:] for group in release_groups}
            warmup_idle = [s for s in samples if s["group"] in warmup_groups and s["kind"] == "postIdle"]
            check(len(warmup_idle) == len(warmup_groups) and {s["group"] for s in warmup_idle} == warmup_groups,
                  "missing or duplicate warmup post-idle samples")
            for sample in [*warmup_idle, *baseline]:
                for key in counters:
                    if int(sample[key]) != resource_reference[key]:
                        failures.append(f"{sample['group']} {sample['kind']} at {sample['monoMs']} ms: "
                                        f"{key}={sample[key]}, cold idle={resource_reference[key]}")
        for group in release_groups:
            duration = [e for e in events if e["group"] == group and e["event"] == "operationUs"]
            idle = [s for s in samples if s["group"] == group and s["kind"] == "postIdle"]
            active = [s for s in samples if s["group"] == group and s["kind"] == "active"]
            expected = set(range(1, requested_rounds+1))
            for records, kind in ((duration, "commands"), (idle, "idle samples"), (active, "active samples")):
                check(len(records) == requested_rounds and {int(x["round"]) for x in records} == expected,
                      f"{group}: missing/duplicate {kind}")
            usec = [int(e["number"]) for e in duration]
            stat = summary([t/1000 for t in usec])
            limit = 1000 if group.endswith("Stop") else 2000
            out = {"commandMs": stat, "missingCount": requested_rounds - len(duration),
                   "overSingleLimit": sum(t > limit*1000 for t in usec),
                   "observedTimeouts": sum("timeout" in e["detail"].lower() for e in events if e["group"] == group and e["event"] == "failure")}
            report["rounds"][group] = out
            if usec:
                is_stop = group.endswith("Stop")
                check(stat["p95"] <= (250 if is_stop else 500) and stat["max"] <= (1000 if is_stop else 2000),
                      group + ": K08 latency exceeded")
            if resource_reference:
                for key in counters:
                    check(all(int(r[key]) == resource_reference[key] for r in idle), group + ": idle mismatch " + key)
                for key in ("nativeInputFd", "fixtureOrPipeFd"):
                    check(all(int(r[key]) > resource_reference[key] for r in active), group + ": missing active observation " + key)
            if base and idle:
                tail = sorted(idle, key=lambda x: int(x["round"]))[-10:]
                pss = (median([int(r["pssKiB"]) for r in tail])-base["pssKiB"]) / 1024
                heap = (median([int(r["nativeBytes"]) for r in tail])-base["nativeBytes"]) / 1048576
                out.update(lastIdleCount=len(tail), pssMedianDeltaMiB=pss, heapMedianDeltaMiB=heap)
                out["memoryBreakdownDeltaMiB"] = memory_deltas(baseline, tail, memory_keys)
                if len(tail) >= 10: check(pss <= 8 and heap <= 4, group + ": idle memory growth")
    else:
        grouped, per_round = by_round(events), by_round(samples)
        long_run = suite in {"q04V01", "q04A03"}
        is_video = suite in {"q04V01", "q04Realtime"}
        cycles = 12 if suite == "q04V01" else 900 if suite == "q04A03" else 1
        reference = json.loads(args.reference.read_text(encoding="utf-8")) if args.reference else None
        if long_run or suite == "q04Realtime":
            if not reference: pending.append("CPU P95 recorded as first baseline; no paired reference")
            if is_video and not args.capture: pending.append("physical output needs calibrated external recording")
            if not is_video: pending.append("external A03 tail recording required for acoustic verification")
        for i in range(1, requested_rounds+1):
            label = (suite, i)
            ev = grouped[label]
            timing = [e for e in ev if e["event"] == "cycleEnd"]
            pcm = [e for e in ev if e["event"] == "audioFrames"]
            vid = [e for e in ev if e["event"] == "videoFrames"]
            rendered = frames[label]
            end = [e for e in ev if e["event"] == "roundEnd"]
            samples_run = [s for s in per_round[label] if s["kind"] == "tick" and s["phase"] == "playing"]
            expected_frames = 28800000 if is_video else 384000
            check(len(timing) == len(pcm) == len(vid) == cycles and len(end) == 1, f"r{i}: incomplete cycles")
            expected_cycles = set(range(1, cycles + 1))
            for kind, records in (("cycleEnd", timing), ("audioFrames", pcm), ("videoFrames", vid)):
                check({int(e["cycle"]) for e in records} == expected_cycles,
                      f"r{i}: missing or unexpected cycle in {kind}")
            check(all(int(e["number"]) == int(e["value"]) == expected_frames for e in pcm), f"r{i}: PCM frame totals")
            check(bool(samples_run), f"r{i}: no active telemetry")
            out = {"completedCycles": len(timing), "backends": sorted({e["detail"] for e in timing if e["detail"]}),
                   "routeTypes": sorted({s["routeType"] for s in samples_run if int(s["routeType"]) > 0})}
            report["rounds"][str(i)] = out
            if not end or not timing: continue
            wall = int(end[0]["value"]) / 1000
            media = int(end[0]["number"])
            check(media == cycles * (600000 if is_video else 8000), f"r{i}: incorrect sample duration")
            out.update(wallMs=wall, mediaMs=media, mediaPerWall=media/wall if wall > 0 else 0,
                       pssKiB=summary([int(s["pssKiB"]) for s in samples_run]),
                       heapBytes=summary([int(s["nativeBytes"]) for s in samples_run]))
            start_ms = int(end[0]["monoMs"]) - wall
            check(wall > 0 and len(samples_run) >= max(2, int(wall/1000)-3), f"r{i}: inadequate playback sampling coverage")
            check(bool(out["backends"]) == is_video, f"r{i}: actual video backend inconsistent with tracks")
            out["playingSamples"] = len(samples_run)
            out["activeResources"] = {key:summary([int(s[key]) for s in samples_run]) for key in
                                      ("fixtureOrPipeFd","nativeInputFd","processFd","processThreads","sdkNamedThreads","liveCodecs")}
            out["thermalStatusValues"] = sorted({int(s["thermal"]) for s in samples_run if int(s["thermal"]) >= 0})
            steady = [s for s in samples_run if int(s["monoMs"]) >= start_ms + min(60000, media/10)
                      and s["state"] == "PLAYING" and float(s["cpuPct"]) >= 0]
            out["steadyCpuOneCorePct"] = summary([float(s["cpuPct"]) for s in steady])
            check(bool(steady) and all(math.isfinite(float(s["cpuPct"])) for s in steady), f"r{i}: missing steady CPU measurements")
            if reference:
                same = (bool(plan.get("serial")) and reference["plan"].get("serial") == plan["serial"]
                        and reference["device"][1:] == report["device"][1:] and reference["fixtures"] == report["fixtures"]
                        and reference["plan"]["suite"] == suite and reference["plan"]["policy"] == plan["policy"])
                other = reference["rounds"].get(str(i), {})
                prior = other.get("steadyCpuOneCorePct", {}).get("p95")
                if same and prior and prior >= 1 and out["backends"] == other.get("backends") and out["routeTypes"] == other.get("routeTypes") and steady:
                    change = out["steadyCpuOneCorePct"]["p95"] / prior - 1
                    out["cpuRelativeChange"] = change
                    check(change <= 0.20, f"r{i}: steady CPU P95 regression")
                else: pending.append(f"r{i}: reference conditions or steady samples do not match")
            if long_run:
                check(wall >= 7200000, f"r{i}: wall duration below 2 hours")
                t0, t1 = int(end[0]["monoMs"]) - wall, int(end[0]["monoMs"])
                first = [s for s in samples_run if t0+60000 <= int(s["monoMs"]) < t0+660000]
                last = [s for s in samples_run if t1-600000 <= int(s["monoMs"]) < t1]
                check(len(first) >= 590 and len(last) >= 590, f"r{i}: incomplete 10-minute memory windows")
                if first and last:
                    for key, scale, budget in (("pssKiB",1024,16),("nativeBytes",1048576,8)):
                        delta = (median([int(s[key]) for s in last])-median([int(s[key]) for s in first]))/scale
                        out[key+"MedianDeltaMiB"] = delta
                        check(delta <= budget, f"r{i}: long-run growth in " + key)
                # Flag stalls inside a cycle only. Open/replay and EOS boundaries stay in raw data.
                for a,b,c in zip(samples_run,samples_run[1:],samples_run[2:]):
                    if a["cycle"] == c["cycle"] and a["state"] == b["state"] == c["state"] == "PLAYING" and a["audioConsumed"] == c["audioConsumed"]:
                        failures.append(f"r{i}: stagnant PCM for two adjacent intervals at {a['monoMs']}")
                        break
            if is_video:
                submitted = sum(int(e["number"]) for e in vid)
                dropped = sum(int(e["value"]) for e in vid)
                for event in vid:
                    delivered, missed = int(event["number"]), int(event["value"])
                    check(delivered >= 0 and missed >= 0 and delivered + missed == 18000,
                          f"r{i} cycle {event['cycle']}: source frame accounting")
                out["submitted"] = submitted
                out["sdkLateDrops"] = dropped
                out["sdkLateDropRate"] = dropped/(18000*cycles)
                out["sdkMaxSubmissionGapMs"] = max((int(e["number"]) for e in ev if e["event"] == "videoMaxSubmitGapMs"), default=None)
                if suite == "q04Realtime":
                    check(0.99 <= out["mediaPerWall"] <= 1.01 and out["sdkLateDropRate"] <= 0.01,
                          f"r{i}: internal speed/drop target exceeded")
                    # This signal describes the actual MediaCodec output Surface, not the physical panel.
                    display = sorted({ns for ns, _ in rendered if ns > 0})
                    pts = {pts for _, pts in rendered}
                    gaps_ms = [(b-a)/1e6 for a,b in zip(display,display[1:])]
                    out.update(codecRenderedCallbacks=len(rendered), codecRenderedUniquePts=len(pts),
                               codecRenderedGapMs=summary(gaps_ms), distinctCodecRenderTimes=len(display))
                    complete_callbacks = submitted > 0 and len(rendered) == len(pts) == len(display) == submitted
                    out["codecRenderTimelineComplete"] = complete_callbacks
                    if not complete_callbacks and not args.capture:
                        pending.append(f"r{i}: incomplete MediaCodec rendered callbacks; external K05 frame trace needed")
                    elif complete_callbacks and gaps_ms:
                        check(max(gaps_ms) <= 200, f"r{i}: codec-rendered Surface gap exceeds 200 ms")
                elif suite == "q04V01": out["codecRenderedCallbacks"] = rendered
    if args.capture:
        try:
            capture, issues = evaluate(args.capture, suite, requested_rounds, report["device"][0].split()[-1],
                                       report["fixtures"][0].split()[0])
            report["externalCapture"] = capture
            failures.extend(issues)
        except (OSError, KeyError, ValueError, ZeroDivisionError) as e:
            failures.append("Invalid external capture: " + str(e))
    report["status"] = "failed" if failures else "pilot" if suite == "q04Pilot" else "pending-evidence" if pending else "measured"
    report["sha256"] = {name: sha256((d/name).read_bytes()).hexdigest() for name in
                        ("samples.csv","events.csv","rendered.csv","inventory.tsv","fixtures.sha256","device.txt","plan.txt","instrumentation.txt")}
    # Queue watchers can read the result as soon as its final name appears.
    payload = json.dumps(report, indent=2, ensure_ascii=False) + "\n"
    with NamedTemporaryFile(dir=d, suffix=".tmp", mode="w", encoding="utf-8", delete=False) as output:
        staged = Path(output.name)
        output.write(payload)
    try:
        staged.replace(d / "summary.json")
    finally:
        staged.unlink(missing_ok=True)
    print(d/"summary.json")
    for item in failures: print("FAILED:", item)
    for item in pending: print("PENDING:", item)
    if failures:
        return 1
    return 2 if suite == "q04Release" and pending else 0


if __name__ == "__main__":
    raise SystemExit(main())
