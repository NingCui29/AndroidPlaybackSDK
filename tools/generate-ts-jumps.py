"""Add a common PTS/DTS/PCR discontinuity to a copy of the synthetic V10 transport stream."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--ffprobe", required=True)
probe = parser.parse_args().ffprobe
source = root / "testdata/formats/V10.ts"
out = root / "testdata/timestamps"
out.mkdir(exist_ok=True)
mask = (1 << 33) - 1


def packets(f):
    data = subprocess.check_output([probe, "-v", "error", "-show_packets", "-show_entries",
                                    "packet=stream_index,pts,dts,pos,size,flags", "-of", "json", str(f)])
    return json.loads(data)["packets"]


def shift_pes(data, i, ticks):
    v = ((data[i] & 14) << 29) | (data[i+1] << 22) | ((data[i+2] & 254) << 14) | (data[i+3] << 7) | (data[i+4] >> 1)
    v = (v + ticks) & mask
    data[i:i+5] = bytes([(data[i] & 0xf1) | ((v >> 29) & 14), (v >> 22) & 255,
                          ((v >> 14) & 254) | 1, (v >> 7) & 255, ((v << 1) & 254) | 1])


original = packets(source)
cut = int([p for p in original if p["stream_index"] == 0 and "K" in p["flags"]][4]["pos"])
assert cut % 188 == 0
manifest = {"sourceSHA256": hashlib.sha256(source.read_bytes()).hexdigest(), "cutBytes": cut, "files": {}}
for direction, ticks in [("forward", 270000), ("backward", -135000)]:
    data = bytearray(source.read_bytes())
    for offset in range(cut, len(data), 188):
        assert data[offset] == 0x47
        afc = (data[offset+3] >> 4) & 3
        pos = offset + 4
        if afc & 2:
            size = data[pos]
            if size >= 7 and data[pos+1] & 0x10:
                base = int.from_bytes(data[pos+2:pos+7], "big")
                data[pos+2:pos+7] = ((((base >> 7) + ticks) & mask) << 7 | (base & 127)).to_bytes(5, "big")
            pos += 1 + size
        if afc & 1 and data[offset+1] & 0x40 and data[pos:pos+3] == b"\x00\x00\x01":
            flags = data[pos+7] >> 6
            if flags & 2:
                shift_pes(data, pos+9, ticks)
            if flags == 3:
                shift_pes(data, pos+14, ticks)
    f = out / f"V10-{direction}.ts"
    f.write_bytes(data)
    modified = packets(f)
    assert len(original) == len(modified)
    first = {}
    for stream in [0, 1]:
        a = [p for p in original if p["stream_index"] == stream]
        b = [p for p in modified if p["stream_index"] == stream]
        changed = False
        assert len(a) == len(b)
        for i, (old, new) in enumerate(zip(a, b)):
            delta = int(new["pts"]) - int(old["pts"])
            assert delta == int(new["dts"]) - int(old["dts"])
            assert old["size"] == new["size"]
            if not changed and delta:
                first[str(stream)] = {"packetIndex": i, "beforePts90k":old["pts"], "newPts90k":new["pts"]}
                changed = True
            assert delta == (ticks if changed else 0)
        assert changed
    manifest["files"][f.name] = {"sha256":hashlib.sha256(data).hexdigest(), "delta90k":ticks, "firstChanged":first}
(out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
