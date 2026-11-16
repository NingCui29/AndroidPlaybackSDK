"""Verify install-set signatures, hash each split, and compare signers across a candidate's R8 hosts."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile
import xml.etree.ElementTree as ET


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--cases", nargs="+", help="Successful build case names. Default: all successful builds.")
    parser.add_argument("--apksigner", type=Path)
    args = parser.parse_args()
    run = args.run.resolve()
    info = json.loads((run / "summary.json").read_text(encoding="utf-8"))
    signer = args.apksigner or Path(os.environ["ANDROID_HOME"]) / "build-tools/35.0.0" / (
            "apksigner.bat" if os.name == "nt" else "apksigner")
    selected = args.cases or [c for c, row in info["cases"].items() if "files" in row and row["outcome"] == "PASS"]
    if not selected:
        raise ValueError("No successful builds to inspect")
    signatures = {}
    certs = set()
    for case in selected:
        row = info["cases"][case]
        if row["outcome"] != "PASS" or row["sdkSha256"] != info["aarSha256"]:
            raise ValueError("Build evidence missing or different AAR")
        base = run / case
        audit = {"sdkSha256": row["sdkSha256"], "packages": {}}
        for name in ("host.apk", "host.apks", "host.aab", "merged-manifest.xml", "mapping.txt"):
            if name in row["files"] and sha(base / name) != row["files"][name]:
                raise ValueError("Archived host digest mismatch: " + name)
        manifest = ET.parse(base / "merged-manifest.xml").getroot()
        android = "{http://schemas.android.com/apk/res/android}"
        uses_sdk = manifest.find("uses-sdk")
        permissions = [node.get(android + "name") for node in manifest if node.tag.startswith("uses-permission")]
        if (uses_sdk is None or uses_sdk.get(android + "minSdkVersion") != "23" or permissions or
                manifest.get("package") != "dev.playback.acceptance." + row["language"]):
            raise ValueError("Unexpected host manifest for " + case)
        mapping = (base / "mapping.txt").read_text(encoding="utf-8")
        player = re.search(r"^com\.cn\.media\.playback\.Player -> ([^:]+):$", mapping, re.M)
        if not player or player.group(1) == "com.cn.media.playback.Player":
            raise ValueError("R8 did not obfuscate the host's Player reference")
        audit.update(minSdk=23, requestedPermissions=permissions, r8PlayerName=player.group(1))
        with tempfile.TemporaryDirectory(prefix="apk-signatures-") as temp:
            packages = [base / "host.apk"]
            if "host.apks" in row["files"]:
                with zipfile.ZipFile(base / "host.apks") as archive:
                    names = sorted(n for n in archive.namelist() if n.startswith("splits/") and n.endswith(".apk"))
                    if "splits/base-arm64_v8a.apk" not in names or "splits/base-master.apk" not in names:
                        raise ValueError("Missing base or ABI split: " + str(names))
                    packages += [Path(archive.extract(n, temp)) for n in names]
            for package in packages:
                result = subprocess.run([str(signer), "verify", "--verbose", "--print-certs", str(package)],
                                        capture_output=True, text=True, errors="replace")
                log = result.stdout + result.stderr
                (base / (package.name + ".apksigner.txt")).write_text(log, encoding="utf-8")
                cert = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-f]{64})$", log, re.M)
                if result.returncode or len(cert) != 1:
                    raise ValueError("Signature validation failed: " + str(package))
                certs.add(cert[0])
                audit["packages"][package.name] = {"sha256": sha(package), "signerSHA256": cert[0]}
        signatures[case] = audit
    if len(certs) != 1:
        raise ValueError("Consumer APK / split signer mismatch")
    (run / "signatures.json").write_text(json.dumps(signatures, indent=2) + "\n", encoding="utf-8")
    print(f"Verified {sum(len(x['packages']) for x in signatures.values())} APK signatures; "
          f"test certificate SHA-256 {certs.pop()}")


if __name__ == "__main__":
    main()
