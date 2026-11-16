"""Write the independent stream and packet references consumed by packet_reference.c."""
import argparse
import json
import subprocess
from hashlib import file_digest
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ffprobe", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("files", nargs="+", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    report = {"ffprobe": subprocess.check_output([args.ffprobe, "-version"], text=True).splitlines()[0],
              "files": {}}
    for path in args.files:
        data = json.loads(subprocess.check_output([args.ffprobe, "-v", "error", "-show_packets",
            "-show_streams", "-show_data_hash", "sha256", "-show_entries",
            "stream=index,time_base:packet=stream_index,pts,dts,size,flags,data_hash", "-of", "json", path]))
        with path.open("rb") as f:
            sha = file_digest(f, "sha256").hexdigest()
        metadata = json.loads((path.parent / "manifest.json").read_text(encoding="utf-8"))
        if sha != metadata["files"][path.name]["sha256"]:
            raise ValueError(f"Fixture differs from its manifest: {path}")
        tracks = {}
        for stream in data["streams"]:
            index = stream["index"]
            num, den = stream["time_base"].split("/")
            reference = args.output / f"{path.stem}-{index}.ref"
            count = 0
            with reference.open("w", encoding="ascii", newline="\n") as out:
                out.write(f"{index} {num} {den}\n")
                for pkt in data["packets"]:
                    if pkt["stream_index"] != index:
                        continue
                    pts = pkt.get("pts", -(1 << 63))
                    dts = pkt.get("dts", -(1 << 63))
                    alg, digest = pkt["data_hash"].split(":")
                    if alg != "SHA256" or len(digest) != 64:
                        raise ValueError(f"Missing packet digest: {path} stream {index} packet {count}")
                    out.write(f"{pts} {dts} {pkt['size']} {int('K' in pkt['flags'])} {digest}\n")
                    count += 1
            with reference.open("rb") as f:
                tracks[index] = {"timeBase": stream["time_base"], "packets": count,
                                 "reference": reference.name, "sha256": file_digest(f, "sha256").hexdigest()}
        report["files"][path.name] = {"sha256": sha, "streams": tracks}
    (args.output / "reference.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
