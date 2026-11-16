"""Inspect the actual AAR's binaries against their private symbols and API 23 stubs."""

from functools import lru_cache
from hashlib import sha256
from pathlib import Path
import re
import subprocess


SAMPLES = {
    "libplayback.so": "Java_com_cn_media_playback_NativeInput_render",
    "libavcodec.so": "avcodec_send_packet",
    "libavformat.so": "av_read_frame",
    "libavutil.so": "av_frame_alloc",
    "libswresample.so": "swr_convert",
    "libswscale.so": "sws_scale",
}


def check(condition, message):
    if not condition:
        raise ValueError(message)


def inspect(aar, temp, root, ndk):
    prebuilt = ndk / "toolchains/llvm/prebuilt"
    tool, = [d for d in prebuilt.iterdir() if (d / "bin").is_dir()]
    revision = (ndk / "source.properties").read_text()
    check(re.search(r"(?m)^Pkg\.Revision\s*=\s*30\.0\.16248370\s*$", revision), "Unexpected NDK")
    stubs = tool / "sysroot/usr/lib/aarch64-linux-android/23"

    def llvm(program, *args):
        return subprocess.check_output([str(tool / "bin" / program), *map(str, args)],
                                       encoding="utf-8", errors="replace")

    def build_id(path):
        found = re.findall(r"Build ID: ([a-f0-9]+)", llvm("llvm-readelf", "-n", path))
        check(len(found) == 1, f"Missing/unexpected Build ID in {path}")
        return found[0]

    @lru_cache(None)
    def symbols(path):
        defined, required, weak = set(), set(), set()
        for line in llvm("llvm-readelf", "--dyn-syms", "--wide", path).splitlines():
            fields = line.split()
            if len(fields) >= 8 and fields[0][:-1].isdigit() and fields[4] in ("GLOBAL", "WEAK"):
                name = fields[7].replace("@@", "@")
                if fields[6] != "UND":
                    defined.add(name)
                    if "@@" in fields[7]:
                        defined.add(name.split("@")[0])
                elif fields[4] == "WEAK":
                    weak.add(name)
                else:
                    required.add(name)
        return defined, required, weak

    members = {m for m in aar.namelist() if m.endswith(".so")}
    check(members == {"jni/arm64-v8a/" + n for n in SAMPLES}, "Incorrect packaged ABI or library set")
    copies = {}
    for name in SAMPLES:
        path = temp / name
        path.write_bytes(aar.read("jni/arm64-v8a/" + name))
        copies[name] = path

    reports, originals = {}, {}
    for name, path in copies.items():
        details = llvm("llvm-readelf", "-h", "-l", "-d", "-S", "-W", path)
        check(re.search(r"Class:\s*ELF64", details) and re.search(r"Machine:\s*AArch64", details)
              and re.search(r"Type:\s*DYN", details), f"Wrong arch/type: {name}")
        check(re.findall(r"\(SONAME\).*?\[(.*?)\]", details) == [name], f"Incorrect SONAME: {name}")
        check(".debug_info" not in details and "(TEXTREL)" not in details, f"Debug info or textrel: {name}")
        loads = [line.split() for line in details.splitlines() if line.strip().startswith("LOAD ")]
        alignments = [int(p[-1], 16) for p in loads]
        check(loads and all(a >= 16384 and a & (a - 1) == 0 for a in alignments)
              and all((int(p[1], 16) - int(p[2], 16)) % 16384 == 0 for p in loads),
              f"PT_LOAD incompatible with 16 KB pages: {name}")
        needed = re.findall(r"\(NEEDED\).*?\[(.*?)\]", details)
        platform = sorted(set(needed) - copies.keys())
        exports = set()
        for dep in needed:
            dependency = copies.get(dep, stubs / dep)
            check(dependency.is_file(), f"{name}: {dep} absent from AAR and API 23 stubs")
            exports.update(symbols(dependency)[0])
        _, imports, weak = symbols(path)
        check(not imports - exports, f"{name}: unresolved imports {sorted(imports - exports)}")
        identity = build_id(path)
        if name == "libplayback.so":
            choices = (root / "sdk/build/intermediates/cxx/RelWithDebInfo").glob("*/obj/arm64-v8a/" + name)
        else:
            choices = [root / "artifacts/n01/arm64-v8a" / name]
        matched = [p for p in choices if build_id(p) == identity]
        check(matched and len({sha256(p.read_bytes()).digest() for p in matched}) == 1,
              f"Missing or ambiguous symbols for {name}/{identity}")
        original = matched[0]
        sections = llvm("llvm-readelf", "-S", "-W", original)
        check(".debug_info" in sections and ".debug_line" in sections, f"Incomplete DWARF: {original}")
        sym = SAMPLES[name]
        matches = re.findall(r"^([a-f0-9]+) T " + re.escape(sym) + r"(?:@@\S+)?$",
                             llvm("llvm-nm", "-D", "--defined-only", path), re.MULTILINE)
        check(len(matches) == 1, f"Missing probe symbol {name}/{sym}")
        fn, loc = llvm("llvm-addr2line", "-f", "-e", original, "0x" + matches[0]).splitlines()[:2]
        check(fn == sym and re.search(r":\d+$", loc) and not loc.endswith(":0"), f"Failed symbolication: {name}/{loc}")
        reports[name] = {
            "buildId": identity, "bytes": path.stat().st_size, "sha256": sha256(path.read_bytes()).hexdigest(),
            "symbolBytes": original.stat().st_size, "symbolSha256": sha256(original.read_bytes()).hexdigest(),
            "machine": "AArch64", "soname": name, "loadAlignments": alignments,
            "needed": needed, "api23PlatformLibraries": platform, "resolvedStrongImports": len(imports),
            "optionalWeakImports": sorted(weak), "symbolication": [matches[0], fn, Path(loc).name],
        }
        originals["arm64-v8a/" + identity + "/" + name] = original
    return reports, originals
