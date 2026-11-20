"""Exercise the release scorer through the same CLI used by the device runner."""

import csv
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCORER = Path(__file__).resolve().parents[1] / "summarize-q04.py"
GROUPS = ("normalStop", "normalRelease", "stalledStop", "stalledRelease")


def write_csv(path, records):
    with path.open("w", newline="", encoding="utf-8") as out:
        writer = csv.DictWriter(out, fieldnames=records[0])
        writer.writeheader()
        writer.writerows(records)


class ReleaseScoring(unittest.TestCase):
    def score(self, rounds, edit=None):
        with tempfile.TemporaryDirectory() as work:
            d = Path(work)
            samples, events = [], []

            def sample(group, kind, n=0):
                active = int(kind == "active")
                mark = dict(group=group, kind=kind, round=n, monoMs=500 * len(samples),
                                    fixtureOrPipeFd=active, nativeInputFd=2 * active, sdkNamedThreads=0,
                                    liveCodecs=0, pssKiB=32768, nativeBytes=4194304, processFd=90, processThreads=30)
                samples.append(mark)
                samples.append(dict(mark, kind="tick", monoMs=mark["monoMs"] + 500))

            sample("warmup", "coldIdle")
            for group in GROUPS:
                sample("warmup" + group[0].upper() + group[1:], "postIdle")
            for _ in range(10):
                sample("warmup", "baseline")
            for group in GROUPS:
                for n in range(1, rounds + 1):
                    sample(group, "active", n)
                    sample(group, "postIdle", n)
                    events.append(dict(group=group, round=n, event="operationUs", number=1000, detail=""))
            events.append(dict(group="suite", round=0, event="suiteFinished", number=0, detail=""))
            if edit:
                edit(samples)
            write_csv(d / "samples.csv", samples)
            write_csv(d / "events.csv", events)
            (d / "plan.txt").write_text(f"suite=q04Release\nrounds={rounds}\npolicy=AUTO\n")
            (d / "instrumentation.txt").write_text("INSTRUMENTATION_CODE: -1\n")
            for name in ("device.txt", "fixtures.sha256", "rendered.csv", "inventory.tsv"):
                (d / name).touch()
            run = subprocess.run([sys.executable, str(SCORER), str(d)], capture_output=True, text=True)
            self.assertTrue((d / "summary.json").is_file(), run.stderr)
            return run.returncode, json.loads((d / "summary.json").read_text(encoding="utf-8"))

    def test_complete_and_short_runs(self):
        for rounds, code, status in ((50, 0, "measured"), (10, 2, "pending-evidence")):
            with self.subTest(rounds=rounds):
                actual, report = self.score(rounds)
                self.assertEqual((actual, report["status"]), (code, status))
                self.assertFalse(report["failures"])

    def test_persistent_and_transient_resource_leaks(self):
        def persistent(rows):
            for row in rows[1:]:
                row["liveCodecs"] = 1

        def transient(rows):
            next(row for row in rows if row["group"] == "warmupStalledStop")["fixtureOrPipeFd"] = 1
            next(row for row in rows if row["kind"] == "baseline")["sdkNamedThreads"] = 1
            next(row for row in rows if row["group"] == "normalStop" and row["kind"] == "postIdle")["nativeInputFd"] = 2

        for edit, fields in ((persistent, ["liveCodecs"]), (transient, ["fixtureOrPipeFd", "sdkNamedThreads", "nativeInputFd"])):
            with self.subTest(case=edit.__name__):
                code, report = self.score(10, edit)
                self.assertEqual((code, report["status"]), (1, "failed"))
                for field in fields:
                    self.assertIn(field, "\n".join(report["failures"]))

    def test_legacy_evidence_preserves_failures(self):
        def legacy(rows):
            rows[:] = [row for row in rows if row["kind"] != "coldIdle" and
                       not (row["group"].startswith("warmup") and row["kind"] == "postIdle")]

        def legacy_growth(rows):
            legacy(rows)
            for row in rows:
                if row["kind"] == "postIdle":
                    row["pssKiB"] += 8193

        code, report = self.score(50, legacy)
        self.assertEqual((code, report["status"]), (2, "pending-evidence"))
        code, report = self.score(10, legacy_growth)
        self.assertEqual((code, report["status"]), (1, "failed"))
        self.assertEqual(len(report["failures"]), 4)

    def test_truncated_periodic_telemetry(self):
        def lost_sampler(rows):
            rows[:] = [row for row in rows if row["kind"] != "tick" or row["group"] == "warmup"]

        code, report = self.score(50, lost_sampler)
        self.assertEqual((code, report["status"]), (1, "failed"))
        self.assertIn("1 Hz telemetry does not cover the release run", report["failures"])


if __name__ == "__main__":
    unittest.main()
