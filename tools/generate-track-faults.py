"""Reproduce A03 track-isolation cases without modifying the source fixture."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--ffprobe", required=True)
args = parser.parse_args()
source = root / "testdata/formats/V02.mov"
data = source.read_bytes()
packets = json.loads(subprocess.check_output([
    args.ffprobe, "-v", "error", "-show_packets", "-show_entries", "packet=pos,size,stream_index",
    "-of", "json", str(source)]))["packets"]
dest = root / "testdata/faults"
dest.mkdir(exist_ok=True)
manifest = {"source": source.name, "sourceSHA256": hashlib.sha256(data).hexdigest(), "files": {}}
for name, failed in [("bad-audio.mov", {1}), ("bad-video.mov", {0}), ("bad-both.mov", {0, 1})]:
    changed = bytearray(data)
    for packet in packets:
        if packet["stream_index"] in failed:
            start, size = int(packet["pos"]), int(packet["size"])
            if start < 0 or start + size > len(data):
                raise ValueError("Unexpected source packet range")
            changed[start:start + size] = bytes([255]) * size
    (dest / name).write_bytes(changed)
    manifest["files"][name] = {"failedTrackIndices": sorted(failed), "sha256": hashlib.sha256(changed).hexdigest()}
(dest / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
