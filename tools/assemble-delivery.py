"""Assemble an immutable local rehearsal from Gradle's file Maven repository."""

import argparse
from hashlib import new, sha256
from io import BytesIO
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

from assemble_elf import check, inspect

ROOT = Path(__file__).resolve().parents[1]
GROUP = "com.cn.media"
ARTIFACT = "android-playback-sdk"
FF_HASH = "7138d28c96d9d3e3af4ee3d8cad72741f8ffb40da90c1112235dea3ecd3178a3"
SIGNER = "FCF986EA15E6E293A5644F10B4322F04D67658D8"
PUBLIC_CLASSES = {"Player", "PlayerConfig", "MediaInfo", "TrackInfo", "MediaSource",
                  "PlayerListener", "PlayerError", "CommandResult", "PlayerSnapshot", "PlaybackStats",
                  "HardwareOutputInfo", "VideoRecoveryInfo"}


def data(source):
    return source.read_bytes() if isinstance(source, Path) else source


def digest(source, algorithm="sha256"):
    return new(algorithm, data(source)).hexdigest()


def sums(entries):
    return "".join(f"{digest(source)}  {name}\n" for name, source in sorted(entries.items())).encode()


def as_json(value):
    return (json.dumps(value, indent=2, ensure_ascii=False, sort_keys=True) + "\n").encode()


def write_zip(path, entries):
    check("SHA256SUMS" not in entries, "Invalid archive entry")
    files = dict(entries, SHA256SUMS=sums(entries))
    with ZipFile(path, "w") as archive:
        for name, source in sorted(files.items()):
            info = ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = (0o100755 if name.endswith(".sh") or name == "gradlew" else 0o100644) << 16
            archive.writestr(info, data(source), compress_type=ZIP_DEFLATED, compresslevel=6)
    # Read back the delivered bytes, including files beyond the AAR.
    with ZipFile(path) as archive:
        check(set(archive.namelist()) == set(files), f"Wrong ZIP contents: {path}")
        for name, source in files.items():
            check(digest(archive.read(name)) == digest(source), f"ZIP readback mismatch: {path}/{name}")


def verify_repo(path, version):
    prefix = ARTIFACT + "-" + version
    products = {ext: path / (prefix + ext) for ext in (".aar", ".pom", ".module", "-sources.jar", "-javadoc.jar")}
    included = {}
    for output in products.values():
        check(output.is_file(), f"Missing Maven artifact {output}")
        included[output.name] = output
        for algo in ("sha256", "sha512", "sha1", "md5"):
            side = path / (output.name + "." + algo)
            check(side.read_text().strip().lower() == digest(output, algo), f"Bad checksum: {side}")
            included[side.name] = side

    pom = ET.parse(products[".pom"]).getroot()
    def field(tag):
        return pom.findtext("{*}" + tag)
    check((field("groupId"), field("artifactId"), field("version"), field("packaging"))
          == (GROUP, ARTIFACT, version, "aar"), "POM coordinates or packaging mismatch")
    check(not pom.findall(".//{*}dependency") and not pom.findall(".//{*}repository"), "Unexpected POM dependency/repository")
    module = json.loads(products[".module"].read_bytes())
    comp = module["component"]
    check((comp["group"], comp["module"], comp["version"]) == (GROUP, ARTIFACT, version), "Module coordinates mismatch")
    variants = {}
    for v in module["variants"]:
        check(not v.get("dependencies") and not v.get("dependencyConstraints"), "Unexpected Gradle dependency")
        attrs = v["attributes"]
        usage = attrs.get("org.gradle.docstype") or attrs["org.gradle.usage"]
        check(usage not in variants, f"Duplicate variant {usage}")
        entry, = v["files"]
        file = included.get(entry["name"])
        check(file and entry["url"] == file.name and file.stat().st_size == entry["size"], f"Bad Gradle file: {entry['name']}")
        for algo in ("sha256", "sha512", "sha1", "md5"):
            check(digest(file, algo) == entry[algo], f"Bad Gradle {algo}: {file}")
        variants[usage] = file.name
    check(variants.get("java-api") == variants.get("java-runtime") == products[".aar"].name
          and variants.get("sources") == products["-sources.jar"].name, "Missing API, runtime or sources")
    with ZipFile(products["-javadoc.jar"]) as docs:
        check(all(f"com/cn/media/playback/{t}.html" in docs.namelist() for t in PUBLIC_CLASSES), "Incomplete Javadoc")
    return products, included, variants, module["createdBy"]["gradle"]["version"]


def verify_signature(temp):
    upstream = ROOT / "third_party/ffmpeg/8.1.3"
    archive = upstream / "ffmpeg-8.1.3.tar.xz"
    signature = upstream / (archive.name + ".asc")
    key = upstream / "ffmpeg-devel.asc"
    check(digest(archive) == FF_HASH, "FFmpeg archive SHA-256 mismatch")
    gpg = shutil.which("gpg") or next((str(p) for p in
            [Path("C:/Program Files/Git/usr/bin/gpg.exe"), Path("C:/msys64/usr/bin/gpg.exe")] if p.is_file()), None)
    check(gpg, "GnuPG is needed to verify FFmpeg")
    home = temp / "gnupg"
    home.mkdir(mode=0o700)
    # A throwaway keyring avoids introducing trust into the host's own ring.
    keyring = home / "ffmpeg.gpg"
    def run(*args):
        result = subprocess.run([gpg, "--homedir", ".", "--no-options", "--batch", *args], cwd=home,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        check(result.returncode == 0, result.stderr.decode(errors="replace"))
        return result.stdout.decode()
    run("--dearmor", "--output", keyring.name, key.as_posix())
    report = run("--no-default-keyring", "--keyring", keyring.name, "--no-auto-key-retrieve",
                 "--status-fd", "1", "--verify", signature.as_posix(), archive.as_posix())
    valid, = [line.split() for line in report.splitlines() if line.startswith("[GNUPG:] VALIDSIG ")]
    check(SIGNER in (valid[2], valid[-1]), "Unexpected FFmpeg release signer")
    return [archive, signature, key, upstream / "SHA256SUMS"], valid[3]


def assemble(args, stage, temp):
    base = Path(*GROUP.split(".")) / ARTIFACT / args.version
    products, files, variants, gradle = verify_repo(args.repo / base, args.version)
    public, private = stage / "public", stage / "private"
    public.mkdir(parents=True)
    private.mkdir()
    direct = public / products[".aar"].name
    shutil.copyfile(products[".aar"], direct)
    check(direct.stat().st_size <= 16 * 1024**2, "AAR exceeds the 16 MiB review threshold")
    source_inputs, signature_date = verify_signature(temp)
    with ZipFile(direct) as aar:
        manifest = ET.fromstring(aar.read("AndroidManifest.xml"))
        check(manifest.get("package") == "com.cn.media.playback" and
              manifest.find("uses-sdk").get("{http://schemas.android.com/apk/res/android}minSdkVersion") == "23",
              "Incorrect namespace or minSdk")
        check(list(e.tag for e in manifest) == ["uses-sdk"], "Unexpected manifest components or permissions")
        rules = aar.read("proguard.txt")
        check(b"com.cn.media.playback.NativeInput" in rules and b"com.cn.media.playback.PlaybackOutput" in rules,
              "Consumer rules missing JNI targets")
        with ZipFile(BytesIO(aar.read("classes.jar"))) as classes:
            check(all(f"com/cn/media/playback/{t}.class" in classes.namelist() for t in PUBLIC_CLASSES),
                  "Missing public entry points")
            licenses = {name: classes.read("META-INF/android-playback/" + name)
                        for name in ["FFmpeg-LGPL-2.1.txt", "FFmpeg-LICENSE.md", "NOTICE.txt"]}
        native, originals = inspect(aar, temp, ROOT, args.ndk)

    repo_zip = public / "offline-maven.zip"
    write_zip(repo_zip, {base.as_posix() + "/" + name: value for name, value in files.items()})
    with ZipFile(repo_zip) as archive:
        check(digest(archive.read(base.as_posix() + "/" + direct.name)) == digest(direct), "Direct/offline AAR differs")
    license_dir = public / "licenses"
    license_dir.mkdir()
    for name, value in licenses.items():
        (license_dir / name).write_bytes(value)
    shutil.copyfile(ROOT / "docs/21-aar-delivery.md", public / "README.md")
    src = {"third_party/ffmpeg/8.1.3/" + p.name: p for p in source_inputs}
    build_files = ("settings.gradle", "build.gradle", "gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar",
                   "gradle/wrapper/gradle-wrapper.properties", "sdk/build.gradle", "sdk/src/main/AndroidManifest.xml",
                   "sdk/consumer-rules.pro")
    with ZipFile(products["-sources.jar"]) as jar:
        for n in jar.namelist():
            if n.endswith("/"):
                continue
            if n.startswith("com/") and n.endswith(".java"):
                src["sdk/src/main/java/" + n] = jar.read(n)
            elif n.startswith("native/"):
                src["sdk/src/main/cpp/" + n.removeprefix("native/")] = jar.read(n)
            elif n == "tools/build-ffmpeg.sh":
                src[n] = jar.read(n)
            elif n.startswith("rebuild/") and n.removeprefix("rebuild/") in build_files:
                src[n.removeprefix("rebuild/")] = jar.read(n)
        check("sdk/src/main/cpp/CMakeLists.txt" in src and "tools/build-ffmpeg.sh" in src
              and all("sdk/src/main/java/com/cn/media/playback/" + t + ".java" in src for t in PUBLIC_CLASSES),
              "Source archive lacks build recipe or SDK sources")
    # Older local rehearsals did not embed the bootstrap; expose exactly which inputs came from the checkout.
    bootstrap_from_checkout = [n for n in build_files if n not in src]
    for n in bootstrap_from_checkout:
        src[n] = ROOT / n
    for name, value in licenses.items():
        src["sdk/src/main/resources/META-INF/android-playback/" + name] = value
    src["docs/21-aar-delivery.md"] = ROOT / "docs/21-aar-delivery.md"
    src["docs/n01-configure-summary.txt"] = ROOT / "docs/n01-configure-summary.txt"
    write_zip(public / "corresponding-source.zip", src)
    metadata = {
        "coordinate": f"{GROUP}:{ARTIFACT}:{args.version}", "kind": "local-rehearsal",
        "minSdk": 23, "abi": ["arm64-v8a"], "newPermissions": [], "externalRuntimeDependencies": [],
        "toolchain": {"gradle": gradle, "ndk": "30.0.16248370", "ffmpeg": "8.1.3"},
        "ffmpegSourceSha256": FF_HASH, "ffmpegPrimaryFingerprint": SIGNER,
        "ffmpegSignatureDate": signature_date, "ffmpegPatches": [],
        "sourceBootstrapFromCheckout": bootstrap_from_checkout,
        "aar": {"name": direct.name, "sha256": digest(direct), "bytes": direct.stat().st_size,
                "meets12MiBTarget": direct.stat().st_size <= 12 * 1024**2},
        "maven": {p.name: digest(p) for p in products.values()}, "gradleVariantFiles": variants,
        "correspondingSourceSha256": digest(public / "corresponding-source.zip"),
        "nativeLibraries": native, "releaseApproval": "pending",
    }
    (public / "candidate.json").write_bytes(as_json(metadata))
    write_zip(private / "native-symbols.zip", {**originals, "candidate.json": as_json(metadata)})
    for folder in (public, private):
        members = {p.relative_to(folder).as_posix(): p for p in folder.rglob("*") if p.is_file()}
        (folder / "SHA256SUMS").write_bytes(sums(members))
    index = {p.relative_to(stage).as_posix(): p for p in stage.rglob("*") if p.is_file()}
    checksums = sums(index)
    (stage / "SHA256SUMS").write_bytes(checksums)
    return digest(direct)[:16] + "-" + sha256(checksums).hexdigest()[:12]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=ROOT / "build/offline-maven")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/distribution")
    parser.add_argument("--version", default="1.0.0-rc.1")
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk and os.environ.get("LOCALAPPDATA"):
        sdk = str(Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk")
    parser.add_argument("--ndk", type=Path, default=Path(sdk or ".") / "ndk/30.0.16248370")
    args = parser.parse_args()
    check(re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:[-.][A-Za-z0-9]+)*", args.version),
          "Expected a fixed Maven version")
    parent = args.output.resolve() / args.version
    parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".assemble-", dir=parent) as work:
        tmp = Path(work)
        stage = tmp / "output"
        key = assemble(args, stage, tmp)
        final = parent / key
        if final.exists():
            for p in stage.rglob("*"):
                if p.is_file():
                    check(digest(p) == digest(final / p.relative_to(stage)), "Existing candidate failed verification")
            check({p.relative_to(stage) for p in stage.rglob("*")} == {p.relative_to(final) for p in final.rglob("*")},
                  "Existing candidate contains unrecorded files")
        else:
            stage.rename(final)
    print(final)


if __name__ == "__main__":
    main()
