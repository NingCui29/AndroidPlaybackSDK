"""Build isolated, R8-minified SDK consumers from candidate files. No device use or uploads."""
import argparse
import contextlib
import datetime
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import urllib.parse
import zipfile
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from apk_native import check_apk, libs_and_alignment

ROOT = Path(__file__).resolve().parents[1]
HOST = ROOT / "acceptance/consumer"
VERSION = "1.0.0-rc.1"
CASES = ("aar-java", "aar-kotlin", "offline-java", "offline-kotlin",
         "http-module-java", "http-pom-kotlin", "missing-aar", "native-collision", "http-401", "http-404", "http-malformed")


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def write_json(path, data):
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def readonly_cache(source, target, inventory):
    # Gradle's repository-specific binary descriptors include dependencies for cached jars.
    # The artifact store is immutable. Copy indexes (never locks); hard-link immutable files.
    modules = source / "modules-2"
    if not (modules / "files-2.1").is_dir():
        raise ValueError("Expected prewarmed Gradle cache with modules-2/files-2.1")
    rows = []
    for origin in modules.rglob("*"):
        rel = origin.relative_to(modules)
        if "com.cn.media" in rel.parts or not origin.is_file():
            continue
        if (rel.parts[0] != "files-2.1" and not rel.parts[0].startswith("metadata-")
                or origin.name.endswith((".lock", ".lck")) or origin.name == "gc.properties"):
            continue
        dest = target / "modules-2" / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        if rel.parts[0] == "files-2.1":
            try:
                os.link(origin, dest)
            except OSError:
                shutil.copyfile(origin, dest)
            rows.append(rel.as_posix())
        else:
            shutil.copyfile(origin, dest)
    inventory.write_text("\n".join(sorted(rows)) + "\n", encoding="utf-8")


@contextlib.contextmanager
def serve_repo(repo, log):
    class Handler(SimpleHTTPRequestHandler):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, directory=str(repo), **kwargs)

        def log_request(self, code="-", size="-"):
            log.append({"method": self.command, "path": self.path, "status": int(code)})

        def send_head(self):
            prefix, sep, name = self.path.removeprefix("/").partition("/")
            if prefix == "denied":
                self.send_response(401)
                self.send_header("WWW-Authenticate", 'Basic realm="candidate"')
                self.end_headers()
                return None
            if prefix == "missing" or not sep:
                self.send_error(404)
                return None
            if prefix == "broken" and name.endswith(".module"):
                data = b'{bad'
                self.send_response(200)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                return io.BytesIO(data)
            if prefix not in {"module", "pom", "broken"}:
                self.send_error(404)
                return None
            return super().send_head()

        def translate_path(self, path):
            return super().translate_path("/" + path.removeprefix("/").partition("/")[2])

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield "http://127.0.0.1:" + str(server.server_port)
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


def archive_build(out, build, aar, split):
    expected = {k: v[0] for k, v in libs_and_alignment(aar, "jni/").items()}
    apk, = (build / "outputs/apk/release").glob("*.apk")
    native = check_apk(apk, expected)
    files = {"host.apk": apk, "mapping.txt": build / "outputs/mapping/release/mapping.txt"}
    manifest, = (build / "intermediates/merged_manifest/release").rglob("AndroidManifest.xml")
    files["merged-manifest.xml"] = manifest
    split_entries = []
    if split:
        bundle, = (build / "outputs/bundle/release").glob("*.aab")
        archive = build / "host.apks"
        files.update({"host.aab": bundle, "host.apks": archive})
        with zipfile.ZipFile(archive) as splits:
            # This host declares no locale resources, density variants or optional features.
            for name in sorted(splits.namelist()):
                if name.startswith("splits/") and name.endswith(".apk"):
                    split_entries.append(name)
            # Inspect the named ABI split itself. A correctly named but empty split
            # cannot load the SDK even if the separate, universal APK is complete.
            arm64_splits = [row for row in split_entries if "arm64_v8a" in row]
            if len(arm64_splits) != 1 or not any("master" in row for row in split_entries):
                raise ValueError(f"Missing required base/ABI splits: {split_entries}")
            split_native = check_apk(io.BytesIO(splits.read(arm64_splits[0])), expected)
            for name in split_entries:
                if name != arm64_splits[0] and libs_and_alignment(io.BytesIO(splits.read(name)), "lib/"):
                    raise ValueError("Unexpected native libraries outside the arm64 ABI split: " + name)
    digests = {}
    for name, origin in files.items():
        dest = out / name
        shutil.copyfile(origin, dest)
        digests[name] = sha(dest)
    result = {"files": digests, "native": native, "aabSplits": split_entries}
    if split:
        result["aabSplitNative"] = {arm64_splits[0]: split_native}
    return result


def run_case(case, gradle, host, cache, work, out, repo, aar, version, endpoint, requests, env, remote):
    lang = "kotlin" if "kotlin" in case else "java"
    metadata = "pom" if "pom" in case else "module"
    collision = case == "native-collision"
    direct = case.startswith("aar-") or collision
    http = case.startswith("http-")
    missing = case == "missing-aar"
    negative = missing or collision or case in {"http-401", "http-404", "http-malformed"}
    paths = {"http-401": "denied", "http-404": "missing", "http-malformed": "broken"}
    url = (remote or endpoint + "/" + paths.get(case, metadata)) if http else repo.as_uri()
    if missing:
        # A separate, intact metadata tree without its AAR. Do not mutate the delivery.
        empty = work / "missing"
        shutil.copytree(repo, empty, ignore=shutil.ignore_patterns("*.aar"))
        url = empty.as_uri()
    out.mkdir()
    config = "aar" if direct else "maven"
    build = work / "built"
    start = len(requests)
    cmd = [str(gradle), "--no-daemon", "--console=plain", "--no-build-cache", "-p", str(host),
           "-g", str(work / "gradle-home"), "--project-cache-dir", str(work / "project-cache"),
           "-PhostOutput=" + str(build), "-PhostLanguage=" + lang, "-PsdkMode=" + config,
           "-PsdkVersion=" + version, "-PsdkMetadata=" + metadata,
           "-PsdkAar=" + str(aar), "-PsdkRepository=" + url]
    if not http:
        cmd.append("--offline")
    elif not remote:
        # A loopback rehearsal must work with no outbound network.
        cmd += ["-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=9",
                "-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=9", "-Dhttp.nonProxyHosts=" + urllib.parse.urlsplit(url).hostname]
    else:
        cmd.append("--info")
        if env.get("PLAYBACK_MAVEN_USER"):
            cmd += ["-PreadonlyIdentity=true"]
    cmd += ["recordResolution"]
    if collision:
        fixture = work / "other-sdk.aar"
        fixture.parent.mkdir(parents=True)
        classes = io.BytesIO()
        with zipfile.ZipFile(classes, "w"):
            pass
        with zipfile.ZipFile(aar) as archive, zipfile.ZipFile(fixture, "w") as other:
            other.writestr("classes.jar", classes.getvalue())
            other.writestr("AndroidManifest.xml", '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.playback.collision"/>')
            native = "jni/arm64-v8a/libavutil.so"
            other.writestr(native, archive.read(native) + b"other-sdk-conflict-fixture")
        cmd += ["-PcollisionAar=" + str(fixture), "mergeReleaseNativeLibs"]
    if not negative:
        cmd += ["assembleRelease"]
        if http:
            cmd += ["buildAcceptanceApks"]
    write_json(out / "gradle-command.json", cmd)
    env = {**env, "GRADLE_RO_DEP_CACHE": str(cache)}
    with (out / "gradle.log").open("w", encoding="utf-8") as stream:
        code = subprocess.run(cmd, env=env, stdout=stream, stderr=subprocess.STDOUT).returncode
    log = (out / "gradle.log").read_text(encoding="utf-8", errors="replace")
    trace = requests[start:]
    write_json(out / "http.json", trace)
    if negative:
        expected = {"missing-aar": aar.name, "native-collision": "2 files found with path 'lib/arm64-v8a/libavutil.so'",
                    "http-401": "401", "http-404": "Could not find",
                    "http-malformed": "Could not parse module metadata"}[case]
        status = {"http-401": 401, "http-404": 404, "http-malformed": 200}.get(case)
        if not code or expected.lower() not in log.lower() or (http and not any(r["status"] == status for r in trace)):
            raise ValueError("Unexpected negative-test outcome; see gradle.log / http.json")
        return {"outcome": "PASS", "expectedFailure": expected, "gradleExit": code, "source": url}
    if code:
        raise ValueError("Gradle failed; see gradle.log")
    if not any(row.startswith("ARTIFACT ") and aar.name in row and sha(aar) in row for row in log.splitlines()):
        raise ValueError("Resolved AAR digest differs from candidate")
    if http and not remote:
        gets = [x["path"] for x in trace if x["status"] == 200 and x["method"] == "GET"]
        if not all(any(path.endswith("." + ext) for path in gets) for ext in ["aar", metadata]):
            raise ValueError("Missing independent transport evidence for AAR or metadata read")
        if metadata == "pom" and any(path.endswith(".module") for path in gets):
            raise ValueError("POM-only protocol fetched Gradle module metadata")
    return {"outcome": "PASS", "language": lang, "sdkSha256": sha(aar), "source": str(aar) if direct else url,
            "releaseMinified": True, **archive_build(out, build, aar, split=http)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    inputs = parser.add_mutually_exclusive_group()
    inputs.add_argument("--repo", type=Path, default=ROOT / "build/offline-maven")
    inputs.add_argument("--offline-zip", type=Path)
    parser.add_argument("--aar", type=Path, help="Standalone direct AAR; default is the copy next to the delivery ZIP")
    parser.add_argument("--version", default=VERSION)
    parser.add_argument("--remote", help="Explicit read-only HTTPS SDK repository; uses both real Maven consumers")
    parser.add_argument("--cases", choices=CASES, nargs="+", default=CASES)
    parser.add_argument("--gradle", type=Path)
    parser.add_argument("--seed-cache", type=Path, default=Path.home()/".gradle/caches")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/consumers")
    parser.add_argument("--scratch", type=Path, default=Path.home()/".gradle",
                        help="Short local path on the same volume as the prewarmed cache")
    args = parser.parse_args()
    if args.remote:
        p = urllib.parse.urlsplit(args.remote)
        if p.scheme != "https" or not p.hostname or p.username or p.password or p.query or p.fragment:
            parser.error("--remote must be an HTTPS repository root without credentials, query or fragment")
        if bool(os.getenv("PLAYBACK_MAVEN_USER")) != bool(os.getenv("PLAYBACK_MAVEN_PASSWORD")):
            parser.error("Set both read-only identity variables or neither")
        args.cases = ["http-module-java", "http-pom-kotlin"]
    gradle = args.gradle or next((Path.home()/".gradle/wrapper/dists/gradle-8.13-bin").glob("*/gradle-8.13/bin/gradle.bat"))
    env = os.environ.copy()
    out = args.output.resolve() / datetime.datetime.now().strftime("run-%Y%m%d-%H%M%S-%f")
    out.mkdir(parents=True)
    summary = {"cases": {}, "device": "not run", "privateRepository": "not run"}
    if args.remote:
        summary["privateRepository"] = args.remote
    print(out, flush=True)
    args.scratch.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="ap-", dir=args.scratch) as tmp:
        work = Path(tmp)
        repo = work / "offline"
        if args.offline_zip:
            summary["offlineZipSha256"] = sha(args.offline_zip)
            with zipfile.ZipFile(args.offline_zip) as z:
                z.extractall(repo)
        else:
            shutil.copytree(args.repo, repo)
        name = f"android-playback-sdk-{args.version}.aar"
        repo_aar, = repo.rglob(name)
        repo = repo_aar.parents[5]
        source_aar = args.aar or (args.offline_zip.parent / name if args.offline_zip else repo_aar)
        if sha(source_aar) != sha(repo_aar):
            raise ValueError("Direct and offline Maven AAR SHA-256 differ")
        aar = work / "direct" / name
        aar.parent.mkdir()
        shutil.copyfile(source_aar, aar)
        host = work / "host"
        shutil.copytree(HOST, host, ignore=shutil.ignore_patterns("build", ".gradle", "local.properties"))
        write_json(out / "host-sources.json", {p.relative_to(host).as_posix(): sha(p)
                   for p in sorted(host.rglob("*")) if p.is_file()})
        summary["hostSourcesSha256"] = sha(out / "host-sources.json")
        summary["javaVersion"] = subprocess.run([str(Path(env["JAVA_HOME"])/"bin/java"), "-version"],
                    capture_output=True, text=True, check=True).stderr.strip()
        summary.update({"coordinate": "com.cn.media:android-playback-sdk:" + args.version, "aarSha256": sha(aar),
                        "toolchain": {"gradle": str(gradle), "agp": "8.13.0", "kgp": "2.1.20", "compileSdk": 35,
                                      "javaHome": env.get("JAVA_HOME"), "androidHome": env.get("ANDROID_HOME")}})
        cache = work / "ro"
        readonly_cache(args.seed_cache, cache, out / "preloaded-tools.txt")
        summary["seedInventorySha256"] = sha(out / "preloaded-tools.txt")
        requests = []
        with serve_repo(repo, requests) as endpoint:
            for case in dict.fromkeys(args.cases):
                print("Running " + case, flush=True)
                case_tmp = work / case
                try:
                    data = run_case(case, gradle, host, cache, case_tmp, out / case, repo, aar,
                                    args.version, endpoint, requests, env, args.remote)
                except Exception as error:
                    data = {"outcome": "FAIL", "error": str(error)}
                summary["cases"][case] = data
                print(case + " " + data["outcome"], flush=True)
                write_json(out / "summary.json", summary)
                # Gradle user homes can be large; tear down the unique scratch dir after its process exits.
                if case_tmp.exists():
                    shutil.rmtree(case_tmp, ignore_errors=True)
    return 0 if all(x["outcome"] == "PASS" for x in summary["cases"].values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
