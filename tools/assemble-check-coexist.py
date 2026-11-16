"""Build an independent two-AAR host: reject a duplicate SONAME, bundle a distinct companion."""

import argparse
from datetime import datetime
from hashlib import sha256
import json
import os
from pathlib import Path
import shutil
import subprocess
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("aar", type=Path)
    args = parser.parse_args()
    aar = args.aar.resolve(strict=True)
    sha = sha256(aar.read_bytes()).hexdigest()
    project = ROOT / "tools/assemble-coexist"
    out = ROOT / "artifacts/distribution/checks" / sha / datetime.now().strftime("%Y%m%d-%H%M%S-coexist")
    out.mkdir(parents=True)
    gradle = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    for label, value in (("duplicate", "ON"), ("distinct", "OFF")):
        command = [str(gradle), "-p", str(project), "--offline", "--console=plain", "clean", ":assembleDebug",
                   "-PplaybackAar=" + str(aar), "-PduplicateName=" + value]
        with (out / (label + ".txt")).open("wb") as f:
            status = subprocess.run(command, stdout=f, stderr=subprocess.STDOUT).returncode
        log = (out / (label + ".txt")).read_text(encoding="utf-8", errors="replace")
        if label == "duplicate":
            if status == 0 or "2 files found with path 'lib/arm64-v8a/libavcodec.so'" not in log:
                raise RuntimeError(f"Expected an AGP duplicate-library failure; inspect {out}/{label}.txt")
        elif status:
            raise RuntimeError(f"Host build failed; inspect {out}/{label}.txt")
    apk, = (project / "build/outputs/apk/debug").glob("*.apk")
    with ZipFile(aar) as src, ZipFile(apk) as dst:
        expected = {f.replace("jni/", "lib/", 1) for f in src.namelist() if f.endswith(".so")}
        expected.add("lib/arm64-v8a/libplayback_companion.so")
        actual = {f for f in dst.namelist() if f.endswith(".so")}
        if actual != expected:
            raise RuntimeError(f"Wrong library set in {apk}: {sorted(actual)}")
        for name in src.namelist():
            if name.endswith(".so") and src.read(name) != dst.read(name.replace("jni/", "lib/", 1)):
                raise RuntimeError("Host changed packaged binary: " + name)
    shutil.copyfile(apk, out / "coexist-debug.apk")
    report = {"aar": aar.name, "aarSha256": sha, "distinctLibraries": sorted(actual),
              "apkSha256": sha256(apk.read_bytes()).hexdigest(), "duplicateRejected": True,
              "duplicate": "lib/arm64-v8a/libavcodec.so", "deviceExecution": "separate check"}
    (out / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(out)


if __name__ == "__main__":
    main()
