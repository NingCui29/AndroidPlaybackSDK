"""Install pinned independent R8 hosts, exercise V01/A01, capture device and playback evidence."""
import argparse
import datetime
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CASES = ["aar-java", "aar-kotlin", "offline-java", "offline-kotlin", "http-module-java", "http-pom-kotlin"]


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", type=Path, required=True, help="Directory written by verify-consumers.py")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--with-track-switch", action="store_true", help="Add V05 for hosts built with dual-track support")
    parser.add_argument("--cases", nargs="+", choices=CASES, default=CASES)
    parser.add_argument("--adb", type=Path, default=shutil.which("adb") or
                        Path(os.getenv("ANDROID_HOME", "")) / "platform-tools/adb.exe")
    args = parser.parse_args()
    run = args.run.resolve()
    info = json.loads((run / "summary.json").read_text(encoding="utf-8"))
    out = run / ("device-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S-%f"))
    out.mkdir()
    prefix = [str(args.adb), "-s", args.serial]

    def adb(*cmd, check=True):
        return subprocess.run([*prefix, *map(str, cmd)], check=check, capture_output=True,
                              encoding="utf-8", errors="replace", timeout=300).stdout.strip()

    def save(name, value):
        (out / name).write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    if adb("get-state") != "device":
        raise ValueError("Required authorized device is unavailable")
    page = adb("shell", "getconf", "PAGE_SIZE", check=False)
    if not page.isdigit():
        maps = adb("shell", "cat", "/proc/self/smaps")
        field = re.search(r"^KernelPageSize:\s+(\d+) kB", maps, re.MULTILINE)
        if not field:
            raise ValueError("Cannot determine the kernel page size")
        page = str(int(field.group(1)) * 1024)
    device = {"serial": args.serial, "pageBytes": page,
              "firmware": adb("shell", "getprop", "ro.build.fingerprint"),
              "sdk": adb("shell", "getprop", "ro.build.version.sdk"),
              "abis": adb("shell", "getprop", "ro.product.cpu.abilist")}
    if "arm64-v8a" not in device["abis"].split(","):
        raise ValueError("Device does not admit arm64-v8a")
    selected = {c: info["cases"][c] for c in args.cases}
    signatures = json.loads((run / "signatures.json").read_text(encoding="utf-8"))
    for case, spec in selected.items():
        if spec["outcome"] != "PASS" or spec["sdkSha256"] != info["aarSha256"]:
            raise ValueError(f"Build evidence unavailable for {case} and the pinned SDK")
        if signatures[case]["sdkSha256"] != info["aarSha256"]:
            raise ValueError("Signature evidence missing for the pinned host")
    fixtures = [(ROOT/"testdata/baseline/V01.mp4", "V01"), (ROOT/"testdata/formats/A01.mp3", "A01")]
    if args.with_track_switch:
        fixtures.append((ROOT/"testdata/formats/V05.mkv", "V05"))
    fingerprints = {p.name: digest(p) for p, _ in fixtures}
    for fixture, _ in fixtures:
        manifest = json.loads((fixture.parent / "manifest.json").read_text(encoding="utf-8"))
        if manifest["files"][fixture.name]["sha256"] != fingerprints[fixture.name]:
            raise ValueError("Fixture digest mismatch: " + fixture.name)
    evidence = {"device": device, "sdkSha256": info["aarSha256"], "fixturesSha256": fingerprints, "cases": {}}
    save("device.json", evidence)
    print(out, flush=True)
    with tempfile.TemporaryDirectory(prefix="consumer-splits-") as temp:
        spec_file = out / "bundletool-device.json"
        def bundletool(task, *properties):
            cmd = [info["toolchain"]["gradle"], "--offline", "--console=plain", "-p",
                   str(ROOT / "acceptance/consumer"), "-PsdkMode=tools",
                   "-PspecOutput=" + str(spec_file), *properties, task]
            with (out / "bundletool.log").open("a", encoding="utf-8") as log:
                subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT, check=True)
        if any(case.startswith("http-") for case in selected):
            bundletool("queryAcceptanceDevice", "-PdeviceId=" + args.serial, "-PadbPath=" + str(args.adb))
        for case, spec in selected.items():
            case_dir = run / case
            package = "dev.playback.acceptance." + spec["language"]
            component = package + "/dev.playback.acceptance.SmokeActivity"
            root = "/sdcard/Android/data/" + package + "/files"
            artifact_name = "host.apks" if case.startswith("http-") else "host.apk"
            artifact = case_dir / artifact_name
            result = {"package": package, "artifact": artifact_name, "samples": {}}
            evidence["cases"][case] = result
            try:
                if digest(artifact) != spec["files"][artifact_name]:
                    raise ValueError("Pinned host digest mismatch")
                if case.startswith("http-"):
                    dest = Path(temp) / case
                    bundletool("selectAcceptanceSplits", "-PapksArchive=" + str(artifact), "-PselectedDir=" + str(dest))
                    local = sorted(dest.rglob("*.apk"))
                    result["splitDigests"] = {p.name: digest(p) for p in local}
                    signed = {row["sha256"] for row in signatures[case]["packages"].values()}
                    if not local or any(sha not in signed for sha in result["splitDigests"].values()):
                        raise ValueError("Selected split does not match signature evidence")
                    with zipfile.ZipFile(case_dir / "host.apk") as standalone:
                        expected = {n for n in standalone.namelist() if n.startswith("lib/") and n.endswith(".so")}
                    actual = set()
                    for package_file in local:
                        with zipfile.ZipFile(package_file) as package_zip:
                            actual.update(n for n in package_zip.namelist() if n.startswith("lib/") and n.endswith(".so"))
                    if not expected or actual != expected:
                        raise ValueError("Installed split selection lacks native dependencies")
                    result["installOutput"] = adb("install-multiple", "-r", *local)
                else:
                    result["installOutput"] = adb("install", "-r", artifact)
                result["installedPaths"] = adb("shell", "pm", "path", package).splitlines()
                if "Success" not in result["installOutput"] or not result["installedPaths"]:
                    raise ValueError("Installation did not complete")
                expected_hashes = (list(result["splitDigests"].values()) if case.startswith("http-")
                                   else [spec["files"][artifact_name]])
                installed_hashes = {}
                for entry in result["installedPaths"]:
                    if not entry.startswith("package:/"):
                        raise ValueError("Unexpected package manager path")
                    apk_path = entry[len("package:"):]
                    with subprocess.Popen([*prefix, "exec-out", "cat", apk_path],
                                          stdout=subprocess.PIPE) as proc:
                        installed_hashes[apk_path] = hashlib.file_digest(proc.stdout, "sha256").hexdigest()
                        if proc.wait() != 0:
                            raise ValueError("Could not read installed consumer APK")
                result["installedSha256"] = installed_hashes
                if sorted(installed_hashes.values()) != sorted(expected_hashes):
                    raise ValueError("Installed consumer APK set differs from pinned artifacts")
                adb("shell", "am", "start", "-W", "-n", component)
                for fixture, sample in fixtures:
                    target = root + "/" + fixture.name
                    existing_hash = adb("shell", "sha256sum", target, check=False).split()
                    if not existing_hash or existing_hash[0] != fingerprints[fixture.name]:
                        adb("push", fixture, target)
                        stored_hash = adb("shell", "sha256sum", target, check=False).split()
                        if not stored_hash or stored_hash[0] != fingerprints[fixture.name]:
                            # Some API 23 images omit sha256sum.
                            with subprocess.Popen([*prefix, "exec-out", "cat", target], stdout=subprocess.PIPE) as stream:
                                actual = hashlib.file_digest(stream.stdout, "sha256").hexdigest()
                                if stream.wait() or actual != fingerprints[fixture.name]:
                                    raise ValueError("Device fixture digest mismatch: " + fixture.name)
                    adb("shell", "am", "force-stop", package)
                    sample_result = root + "/" + sample + ".json"
                    adb("shell", "rm", "-f", sample_result)
                    adb("shell", "am", "start", "-W", "-n", component, "--es", "sample", sample)
                    deadline = time.monotonic() + 75
                    while time.monotonic() < deadline:
                        raw = adb("shell", "cat", sample_result, check=False)
                        if raw.startswith("{"):
                            row = result["samples"][sample] = json.loads(raw)
                            if row.get("outcome") != "PASS" or row.get("sample") != sample:
                                raise ValueError(f"{sample} failed: {row}")
                            break
                        time.sleep(0.25)
                    else:
                        raise TimeoutError("No completion record for " + sample)
                result["outcome"] = "PASS"
            except Exception as error:
                result.update(outcome="FAIL", error=str(error))
            save("device.json", evidence)
            print(case + " " + result["outcome"], flush=True)
    return 0 if all(row["outcome"] == "PASS" for row in evidence["cases"].values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
