"""Verify one sealed release candidate and optionally stage its verified files."""

import argparse
import hashlib
from io import BytesIO
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from zipfile import BadZipFile, ZipFile


ROOT = Path(__file__).resolve().parents[1]
GROUP = "com.cn.media"
ARTIFACT = "android-playback-sdk"
ANDROID = "{http://schemas.android.com/apk/res/android}"
HASH = r"[0-9a-f]{64}"
MAVEN_SUFFIXES = (".aar", ".pom", ".module", "-sources.jar", "-javadoc.jar")
SIDECARS = ("md5", "sha1", "sha256", "sha512")


def check(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def checksum_manifest(data):
    entries = {}
    for line in data.decode("utf-8").splitlines():
        item = re.fullmatch(r"(" + HASH + r")  (.+)", line)
        check(item is not None, "Invalid SHA256SUMS line")
        digest, name = item.groups()
        check(name not in entries, "Duplicate SHA256SUMS entry: " + name)
        entries[name] = digest
    return entries


def verify(base, tag, candidate_id, approved_manifest):
    check(re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?", tag),
          "Expected a version tag such as v1.1.0")
    check(re.fullmatch(r"[0-9a-f]{16}-[0-9a-f]{12}", candidate_id), "Invalid candidate directory ID")
    check(re.fullmatch(HASH, approved_manifest), "Expected the full SHA-256 of public/SHA256SUMS")

    version = tag[1:]
    public = base / version / candidate_id / "public"
    check(public.is_dir() and public.resolve() == base.resolve() / version / candidate_id / "public",
          "Exact candidate directory not found")
    manifest_bytes = (public / "SHA256SUMS").read_bytes()
    check(sha(manifest_bytes) == approved_manifest, "Public manifest differs from the approved SHA-256")
    manifest = checksum_manifest(manifest_bytes)
    public_files = {p.relative_to(public).as_posix(): p for p in public.rglob("*") if p.is_file()}
    del public_files["SHA256SUMS"]
    check(public_files.keys() == manifest.keys(), "Public files differ from manifest inventory")
    verified_files = {}
    for name, path in public_files.items():
        check(path.resolve() == public.resolve() / name, "Unexpected linked public file: " + name)
        data = path.read_bytes()
        check(sha(data) == manifest[name], "Public file digest mismatch: " + name)
        verified_files[name] = data

    candidate = json.loads(verified_files["candidate.json"])
    check(candidate["coordinate"] == f"{GROUP}:{ARTIFACT}:{version}", "Candidate coordinate differs from tag")
    stem = f"{ARTIFACT}-{version}"
    aar = stem + ".aar"
    maven = candidate["maven"]
    check(maven.keys() == {stem + suffix for suffix in MAVEN_SUFFIXES}, "Candidate Maven inventory mismatch")
    check(candidate["aar"]["name"] == aar and candidate["aar"]["sha256"] == maven[aar] == manifest[aar] and
          candidate_id.startswith(manifest[aar][:16] + "-"), "Candidate AAR identity mismatch")
    check(candidate["correspondingSourceSha256"] == manifest["corresponding-source.zip"],
          "Corresponding source digest mismatch")

    prefix = f"{GROUP.replace('.', '/')}/{ARTIFACT}/{version}/"
    payload = {}
    with ZipFile(BytesIO(verified_files["offline-maven.zip"])) as archive:
        payload_paths = {prefix + name for name in maven}
        archive_paths = payload_paths | {name + "." + alg for name in payload_paths for alg in SIDECARS}
        entries = archive.namelist()
        check(len(entries) == len(set(entries)) and set(entries) == archive_paths | {"SHA256SUMS"},
              "Offline Maven archive inventory mismatch")
        archive_manifest = checksum_manifest(archive.read("SHA256SUMS"))
        check(archive_manifest.keys() == archive_paths, "Offline Maven manifest inventory mismatch")
        for name in maven:
            path = prefix + name
            data = archive.read(path)
            check(sha(data) == archive_manifest[path] == maven[name], "Maven payload digest mismatch: " + name)
            for alg in SIDECARS:
                sidecar = path + "." + alg
                contents = archive.read(sidecar)
                expected = hashlib.new(alg, data).hexdigest()
                check(sha(contents) == archive_manifest[sidecar] and contents.decode("ascii").strip() == expected,
                      "Maven checksum mismatch: " + sidecar)
            payload[path] = data
    pom = ET.fromstring(payload[prefix + stem + ".pom"])
    check(tuple(pom.findtext("{*}" + field) for field in ("groupId", "artifactId", "version", "packaging"))
          == (GROUP, ARTIFACT, version, "aar"), "POM coordinate or packaging differs from tag")
    with ZipFile(BytesIO(verified_files[aar])) as android_archive:
        sdk = ET.fromstring(android_archive.read("AndroidManifest.xml")).find("uses-sdk")
    minimum = candidate.get("minSdk")
    check(isinstance(minimum, int) and minimum >= 21 and sdk is not None and
          sdk.get(ANDROID + "minSdkVersion") == str(minimum) == pom.findtext("{*}properties/{*}android.minSdk"),
          "Candidate, AAR, and POM minSdk differ")
    component = json.loads(payload[prefix + stem + ".module"])["component"]
    check((component["group"], component["module"], component["version"]) == (GROUP, ARTIFACT, version),
          "Gradle Module Metadata coordinate differs from tag")
    return verified_files, payload, aar


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--candidate-id", required=True)
    parser.add_argument("--manifest-sha256", required=True, help="Approved SHA-256 of public/SHA256SUMS")
    parser.add_argument("--distribution", type=Path, default=ROOT / "artifacts/distribution")
    parser.add_argument("--output", type=Path, help="New staging directory for verified files")
    args = parser.parse_args()
    try:
        public, payload, aar = verify(args.distribution, args.tag, args.candidate_id, args.manifest_sha256)
        if args.output:
            args.output.mkdir(parents=True)
            release = args.output / "release"
            release.mkdir()
            for name in (aar, "offline-maven.zip", "corresponding-source.zip", "candidate.json"):
                (release / name).write_bytes(public[name])
            for name, data in payload.items():
                destination = args.output / "maven" / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(data)
    except (OSError, ValueError, TypeError, KeyError, BadZipFile, ET.ParseError) as error:
        parser.exit(1, f"Release verification failed: {error}\n")
    print(f"Verified {args.tag} candidate {args.candidate_id}, manifest {args.manifest_sha256}")


if __name__ == "__main__":
    main()
