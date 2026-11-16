"""Compare the sealed AAR's NAL adapter with independently normalized desktop ffprobe packets."""
import argparse
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
from tempfile import TemporaryDirectory
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]


def hexdump(text):
    return b"".join(bytes.fromhex(line[10:49]) for line in text.splitlines() if line.strip())


def annex_b(packet, width):
    if width:
        units = []
        end = 0
        while end < len(packet):
            size = int.from_bytes(packet[end:end+width], "big")
            start = end + width
            end = start + size
            if not size or end > len(packet):
                raise ValueError("Invalid length-prefixed reference")
            units.append(packet[start:end])
    else:
        start, *units = re.split(rb"\x00{2,}\x01", packet)
        if start or not units or not all(units):
            raise ValueError("Invalid Annex B reference")
        units[-1] = units[-1].rstrip(b"\0")
    return b"".join(b"\0\0\0\1" + u for u in units)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for a in ("candidate", "ffprobe", "jdk", "out"):
        parser.add_argument("--"+a, required=True, type=Path)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=False)
    info = json.loads(args.candidate.read_text())
    aar = args.candidate.with_name(info["aar"]["name"])
    if hashlib.sha256(aar.read_bytes()).hexdigest() != info["aar"]["sha256"]:
        raise ValueError("Candidate digest mismatch")
    report = {"aarSha256": info["aar"]["sha256"], "samples": {},
              "ffprobe": subprocess.check_output([args.ffprobe, "-version"], text=True).splitlines()[0]}
    with TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        jar = tmp / "classes.jar"
        with ZipFile(aar) as z:
            jar.write_bytes(z.read("classes.jar"))
        sources = ROOT/"native-tests"
        subprocess.run([args.jdk/"bin/javac", "-cp", jar, "-d", tmp, sources/"NalPacketChecks.java",
                        sources/"NalAdapterChecks.java"], check=True)
        import os
        cmd = [args.jdk/"bin/java", "-cp", str(jar)+os.pathsep+str(tmp), "com.cn.media.playback."]
        def run(name, arg):
            return subprocess.check_output([*cmd[:-1], cmd[-1]+name, arg], text=True).strip()
        report["parameterVariants"] = run("NalAdapterChecks", ROOT/"testdata/hardware").splitlines()
        for name in ("V01.mp4", "V03.mp4", "V04.mkv", "V10.ts", "V11.mp4"):
            file = ROOT/"testdata"/("baseline" if name.startswith(("V01","V11")) else "formats")/name
            with file.open("rb") as f:
                digest = hashlib.file_digest(f, "sha256").hexdigest()
            if digest != json.loads((file.parent/"manifest.json").read_text())["files"][name]["sha256"]:
                raise ValueError("Undeclared fixture bytes")
            limit = ["-read_intervals", "%+#320"] if name.startswith("V01") else []
            data = json.loads(subprocess.check_output([args.ffprobe,"-v","error","-select_streams","v:0",*limit,
                    "-show_entries","stream=codec_name,extradata,time_base:packet=pts,dts,data,flags,size",
                    "-show_data","-of","json",file]))
            stream, = data["streams"]
            extra = hexdump(stream["extradata"])
            hevc = stream["codec_name"] == "hevc"
            width = ((extra[21 if hevc else 4] & 3)+1) if extra.startswith(b"\1") else 0
            rows = data["packets"]
            transcript = hashlib.sha256()
            pts_order = []
            with (tmp/"ref").open("wb") as out:
                out.write(struct.pack(">?i",hevc,len(extra))+extra+struct.pack(">i",len(rows)))
                for row in rows:
                    raw = hexdump(row["data"])
                    if len(raw) != int(row["size"]):
                        raise ValueError("ffprobe packet length mismatch")
                    timestamps = struct.pack(">qq",row.get("pts",-(1<<63)),row.get("dts",-(1<<63)))
                    normalized = hashlib.sha256(annex_b(raw,width)).digest()
                    out.write(timestamps+struct.pack(">i",len(raw))+raw+normalized)
                    transcript.update(timestamps+normalized)
                    if "pts" in row: pts_order.append(row["pts"])
            result = run("NalPacketChecks",tmp/"ref")
            report["samples"][name] = {"fixtureSha256": digest, "packets": len(rows), "timeBase": stream["time_base"],
                    "scope": "first 320 demuxed video packets" if limit else "full video stream",
                    "lengthBytes": width, "decreasingPtsPairs": sum(b<a for a,b in zip(pts_order,pts_order[1:])),
                    "timestampAndAnnexBHash": transcript.hexdigest(), "result": result}
    (args.out/"report.json").write_text(json.dumps(report,indent=2)+"\n")


if __name__ == "__main__":
    main()
