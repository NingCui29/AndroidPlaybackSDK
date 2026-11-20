"""Pin the device suites to one sealed release AAR; build and audit their self-instrumenting APK."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZIP_DEFLATED

from apk_native import check_apk

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.cn.media.playback.test"
RUNNER = "com.cn.media.playback.InputChecks"
ANDROID = "{http://schemas.android.com/apk/res/android}"
TARGET_SDK = 35


def digest(data):
    return hashlib.sha256(data).hexdigest()


def log(command, path, cwd=None):
    with path.open("wb") as stream:
        subprocess.run(command, cwd=cwd, stdout=stream, stderr=subprocess.STDOUT, check=True)
    return path.read_text(encoding="utf-8", errors="replace")


def dex_classes(tool, dex):
    report = subprocess.check_output([tool, dex], encoding="utf-8", errors="replace")
    return set(re.findall(r"^\s*Class descriptor\s*:\s*'L([^']+);'$", report, re.M))


def pin_inputs(inputs, out):
    suite = ROOT / "sdk/src/androidTest"
    host = ROOT / "acceptance/candidate-instrumentation"
    sources = {Path("suite") / p.relative_to(suite): p for p in suite.rglob("*") if p.is_file()}
    for name in ("settings.gradle", "build.gradle", "src/main/AndroidManifest.xml"):
        sources[Path("host") / name] = host / name
    inventory = {}
    with ZipFile(out / "instrumentation-inputs.zip", "w", ZIP_DEFLATED) as bundle:
        for rel, source in sorted(sources.items()):
            data = source.read_bytes()
            target = inputs / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            inventory[rel.as_posix()] = digest(data)
            bundle.writestr(rel.as_posix(), data)
    return inventory


def candidate_min_sdk(candidate, aar_bytes):
    with ZipFile(io.BytesIO(aar_bytes)) as archive:
        manifest = ET.fromstring(archive.read("AndroidManifest.xml"))
    sdk = manifest.find("uses-sdk")
    declared = sdk.get(ANDROID + "minSdkVersion") if sdk is not None else None
    minimum = candidate.get("minSdk")
    if (not isinstance(minimum, int) or not 21 <= minimum <= TARGET_SDK or declared != str(minimum)
            or candidate.get("abi") != ["arm64-v8a"]):
        raise ValueError("Candidate minSdk or ABI does not match the arm64 instrumentation contract")
    return minimum


def verify_manifest(build, out, minimum):
    path, = (build / "intermediates/packaged_manifests/debugAndroidTest").rglob("AndroidManifest.xml")
    shutil.copyfile(path, out / "manifest.xml")
    manifest = ET.parse(path).getroot()
    runner, sdk, app = manifest.find("instrumentation"), manifest.find("uses-sdk"), manifest.find("application")
    if (manifest.get("package") != PACKAGE or runner.get(ANDROID + "targetPackage") != PACKAGE or
            runner.get(ANDROID + "name") != RUNNER or sdk.get(ANDROID + "minSdkVersion") != str(minimum) or
            sdk.get(ANDROID + "targetSdkVersion") != str(TARGET_SDK) or app.get(ANDROID + "debuggable") != "true" or
            app.find("provider").get(ANDROID + "authorities") != PACKAGE + ".fixture" or
            app.find("activity").get(ANDROID + "name") != "com.cn.media.playback.DisplayActivity"):
        raise ValueError("Manifest does not match the existing device suite contract")


def verify_apk_sdk(aapt, apk, minimum, report):
    badging = log([aapt, "dump", "badging", apk], report)
    for field, expected in (("minSdkVersion", minimum), ("targetSdkVersion", TARGET_SDK)):
        values = re.findall(rf"(?m)^{field}:'(\d+)'\s*$", badging)
        if values != [str(expected)]:
            raise ValueError(f"Packaged APK {field} differs from {expected}: {values}")


def inspect_dex(aar, apk, build, scratch, tool):
    with ZipFile(aar) as archive:
        jar = archive.read("classes.jar")
    with ZipFile(io.BytesIO(jar)) as classes:
        expected = {p[:-6] for p in classes.namelist() if p.endswith(".class")}
    external = build / "intermediates/dex/debugAndroidTest/mergeExtDexDebugAndroidTest"
    sdk_dex, = external.glob("*.dex")
    sdk_classes = dex_classes(tool, sdk_dex)
    if not expected or expected - sdk_classes:
        raise ValueError("SDK classes absent from the external-dependency DEX: " + str(expected - sdk_classes))
    sdk_dex_hash = digest(sdk_dex.read_bytes())
    groups, test_classes = {}, set()
    with ZipFile(apk) as archive:
        for name in archive.namelist():
            if not re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", name):
                continue
            data = archive.read(name)
            sha = digest(data)
            groups[name] = sha
            if sha != sdk_dex_hash:
                path = scratch / name
                path.write_bytes(data)
                test_classes.update(dex_classes(tool, path))
    if list(groups.values()).count(sdk_dex_hash) != 1 or test_classes & sdk_classes:
        raise ValueError("Cannot uniquely trace APK SDK bytecode to the candidate-only DEX")
    harness = {p.stem for p in (scratch / "suite/java").rglob("*.java")}
    if any("com/cn/media/playback/" + c not in test_classes for c in harness) or "InputChecks" not in harness:
        raise ValueError("Device test implementation classes missing from the signed APK")
    return {"classesJarSha256": digest(jar), "aarClassCount": len(expected),
            "sdkDexSha256": sdk_dex_hash, "apkDex": groups, "testSuites": sorted(harness)}


def build(candidate_path, out):
    candidate = json.loads(candidate_path.read_text(encoding="utf-8"))
    aar = candidate_path.with_name(candidate["aar"]["name"])
    sha = candidate["aar"]["sha256"]
    aar_bytes = aar.read_bytes()
    if digest(aar_bytes) != sha:
        raise ValueError("Candidate digest and AAR do not match")
    minimum = candidate_min_sdk(candidate, aar_bytes)
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/35.0.0"
    win = os.name == "nt"
    with tempfile.TemporaryDirectory(prefix="playback-candidate-suite-") as temp:
        work = Path(temp)
        inventory = pin_inputs(work, out)
        pinned = work / aar.name
        pinned.write_bytes(aar_bytes)
        build_dir = work / "build"
        gradle = ROOT / ("gradlew.bat" if win else "gradlew")
        output = log([gradle, "--offline", "--no-build-cache", "-p", work / "host",
                      "-PcandidateAar=" + str(pinned), f"-PcandidateMinSdk={minimum}",
                      "-PsuiteRoot=" + str(work / "suite"),
                      "-PhostOutput=" + str(build_dir), "recordCandidate", "assembleDebugAndroidTest"],
                     out / "gradle.log", ROOT)
        if ("SDK_AAR " + str(pinned)) not in output.splitlines() or digest(pinned.read_bytes()) != sha:
            raise ValueError("Instrumentation runtime classpath differs from the sealed input")
        built, = (build_dir / "outputs/apk/androidTest/debug").glob("*.apk")
        apk = out / "test.apk"
        shutil.copyfile(built, apk)
        verify_manifest(build_dir, out, minimum)
        verify_apk_sdk(tools / ("aapt2.exe" if win else "aapt2"), apk, minimum, out / "apk-badging.txt")
        signatures = log([tools / ("apksigner.bat" if win else "apksigner"), "verify", "--verbose",
                          "--print-certs", apk], out / "signatures.txt")
        signer, = re.findall(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})", signatures)
        dex = inspect_dex(pinned, apk, build_dir, work, tools / ("dexdump.exe" if win else "dexdump"))
        libs = {"arm64-v8a/" + name: item["sha256"] for name, item in candidate["nativeLibraries"].items()}
        if len(libs) != 6 or "arm64-v8a/libplayback.so" not in libs:
            raise ValueError("Unexpected candidate native inventory")
        native = check_apk(apk, libs)
    report = {"coordinate": candidate["coordinate"], "aarSha256": sha, "apkSha256": digest(apk.read_bytes()),
              "testInputsSha256": digest((out / "instrumentation-inputs.zip").read_bytes()),
              "inputs": inventory, "package": PACKAGE, "targetPackage": PACKAGE, "runner": RUNNER,
              "signerSha256": signer, "dex": dex, "native": native, "minSdk": minimum,
              "targetSdk": TARGET_SDK, "deviceRun": False}
    (out / "provenance.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(apk)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="New evidence directory")
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    build(args.candidate.resolve(), out)
