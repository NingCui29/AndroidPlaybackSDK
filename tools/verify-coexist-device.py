"""Run the two-JNI host whose APK was pinned by assemble-check-coexist.py."""
import argparse
from datetime import datetime
from hashlib import file_digest
import json
from pathlib import Path
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    report = json.loads((args.run / "report.json").read_text(encoding="utf-8"))
    apk = args.run / "coexist-debug.apk"
    with apk.open("rb") as stream:
        if file_digest(stream, "sha256").hexdigest() != report["apkSha256"]:
            raise ValueError("Host APK differs from build evidence")

    def adb(*cmd):
        return subprocess.check_output(["adb", "-s", args.serial, *cmd],
                                       text=True, timeout=90).strip()

    package = "com.cn.media.playback.coexist"
    evidence = {"serial": args.serial, "aarSha256": report["aarSha256"],
                "apkSha256": report["apkSha256"], "outcome": "FAIL"}
    output = args.run / f"device-{datetime.now():%Y%m%d-%H%M%S-%f}.json"
    try:
        evidence["firmware"] = adb("shell", "getprop", "ro.build.fingerprint")
        evidence["install"] = adb("install", "-r", str(apk))
        location = adb("shell", "pm", "path", package)
        if not location.startswith("package:") or "\n" in location:
            raise ValueError("Unexpected installed APK path")
        installed = adb("shell", "sha256sum", location.removeprefix("package:")).split()[0]
        evidence["installedSha256"] = installed
        if installed != report["apkSha256"]:
            raise ValueError("Installed APK differs from build evidence")
        adb("shell", "am", "force-stop", package)
        # Use the device clock and a fresh process so prior runs cannot satisfy this check.
        earliest = float(adb("shell", "date", "+%s.%N"))
        evidence["launch"] = adb("shell", "am", "start", "-W", "-n", package + "/.CoexistActivity")
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            for line in adb("logcat", "-d", "-v", "epoch", "-s", "PlaybackCoexist:I").splitlines():
                fields = line.split()
                if (fields and fields[0].replace(".", "", 1).isdigit()
                        and float(fields[0]) >= earliest
                        and "both JNI libraries loaded; marker=2301, state=IDLE" in line):
                    evidence.update(outcome="PASS", runtimeLog=line)
                    return
            time.sleep(0.3)
        raise RuntimeError("No fresh success marker for both JNI libraries")
    except Exception as exc:
        evidence["error"] = str(exc)
        raise
    finally:
        output.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
        print(output)


if __name__ == "__main__":
    main()
