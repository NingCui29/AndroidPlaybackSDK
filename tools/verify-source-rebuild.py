"""Audit a clean SDK AAR rebuilt from the candidate's corresponding-source.zip."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import struct
import xml.etree.ElementTree as ET
from zipfile import ZipFile


def sha(data):
    return hashlib.sha256(data).hexdigest()


def contents(file):
    with ZipFile(file) as archive:
        return {name: sha(archive.read(name)) for name in archive.namelist() if not name.endswith("/")}


def elf_sections(data):
    if not data.startswith(b"\x7fELF\x02\x01"):
        raise ValueError("Expected a little-endian 64-bit ELF")
    offset = struct.unpack_from("<Q", data, 40)[0]
    size, count, names = struct.unpack_from("<HHH", data, 58)
    headers = [struct.unpack_from("<IIQQQQIIQQ", data, offset + i * size) for i in range(count)]
    string_header = headers[names]
    strings = data[string_header[4]:string_header[4] + string_header[5]]
    return {strings[h[0]:].split(b"\0", 1)[0].decode(): sha(data[h[4]:h[4]+h[5]])
            for h in headers if h[0] and h[1] != 8}


def main():
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument("--public", type=Path, required=True)
    args.add_argument("--rebuilt", type=Path, required=True)
    args.add_argument("--lint", type=Path, required=True)
    args.add_argument("--output", type=Path, required=True)
    options = args.parse_args()
    candidate = json.loads((options.public / "candidate.json").read_text(encoding="utf-8"))
    direct = options.public / candidate["aar"]["name"]
    if (sha(direct.read_bytes()) != candidate["aar"]["sha256"] or
            sha((options.public / "corresponding-source.zip").read_bytes()) != candidate["correspondingSourceSha256"]):
        raise ValueError("Candidate AAR or source archive digest differs from the declared input")
    original, rebuilt = contents(direct), contents(options.rebuilt)
    if original.keys() != rebuilt.keys():
        raise ValueError("Rebuilt AAR entry inventory changed")
    jni = "jni/arm64-v8a/libplayback.so"
    with ZipFile(direct) as delivered, ZipFile(options.rebuilt) as built:
        before = contents(io.BytesIO(delivered.read("classes.jar")))
        after = contents(io.BytesIO(built.read("classes.jar")))
        left, right = elf_sections(delivered.read(jni)), elf_sections(built.read(jni))
    changed_sections = [name for name in sorted(left.keys() | right.keys()) if left.get(name) != right.get(name)]
    changed_entries = [name for name in original if original[name] != rebuilt[name]]
    issues = ET.parse(options.lint).getroot().findall("issue")
    payload_matches = (before == after and set(changed_sections) <= {".note.gnu.build-id"}
                       and set(changed_entries) <= {"classes.jar", jni})
    verified = payload_matches and not issues
    record = {"aar": candidate["aar"]["sha256"], "correspondingSource": candidate["correspondingSourceSha256"],
              "rebuiltAar": sha(options.rebuilt.read_bytes()), "aarEntriesChanged": changed_entries,
              "allClassesAndResourcesIdentical": before == after, "classCount": sum(s.endswith(".class") for s in before),
              "jniSectionsChanged": changed_sections, "payloadMatchesExceptBuildId": payload_matches,
              "lintIssues": [issue.get("id") for issue in issues],
              "ffmpegLibraries": {n: original[n] for n in sorted(original) if n.endswith(".so") and n != jni},
              "outcome": "PASS" if verified else "FAIL"}
    options.output.write_text(json.dumps(record, indent=2)+"\n", encoding="utf-8")
    if not verified:
        raise ValueError("Rebuild or lint gate failed; see " + str(options.output))
    print(options.output)


if __name__ == "__main__":
    main()
