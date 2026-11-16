"""Build synthetic AVC/HEVC configuration changes with independent sample descriptions."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import tempfile

U32 = struct.Struct(">I")


def boxes(blob):
    pos = 0
    while pos < len(blob):
        size = U32.unpack_from(blob, pos)[0]
        if size < 8 or size > len(blob) - pos:
            raise ValueError("Expected a complete 32-bit MP4 box")
        yield blob[pos + 4:pos + 8], blob[pos + 8:pos + size]
        pos += size


def box(kind, data):
    return U32.pack(len(data) + 8) + kind + data


def path(blob, *names):
    for name in names:
        blob = dict(boxes(blob))[name]
    return blob


def table(entries):
    return b"\0" * 4 + U32.pack(len(entries)) + b"".join(U32.pack(n) for row in entries for n in row)


def make_mp4(segments, bad_length=False):
    # Each source has one track, one chunk of 30 samples, a single IDR, no edits and no B frames.
    data = [p.read_bytes() for p in segments]
    sample_tables = [dict(boxes(path(b, b"moov", b"trak", b"mdia", b"minf", b"stbl"))) for b in data]
    chunks, sizes = [], []
    for b, t in zip(data, sample_tables):
        assert U32.unpack_from(t[b"stco"], 4)[0] == 1
        assert b"ctts" not in t
        assert t[b"stsc"] == table([(1,30,1)]) and t[b"stss"] == table([(1,)])
        fixed_size, count = struct.unpack_from(">II", t[b"stsz"], 4)
        assert count == 30
        lengths = [fixed_size]*count if fixed_size else list(struct.unpack_from(">30I", t[b"stsz"], 12))
        offset = U32.unpack_from(t[b"stco"], 8)[0]
        chunks.append(b[offset:offset+sum(lengths)])
        sizes.extend(lengths)
    if bad_length:
        # Mark the second sample's NAL as longer than its remaining demuxed data.
        pos = sizes[0]
        chunks[0] = chunks[0][:pos] + U32.pack(sizes[1]+1) + chunks[0][pos+4:]
    ftyp = box(b"ftyp", path(data[0], b"ftyp"))
    offsets = [len(ftyp)+8+sum(len(c) for c in chunks[:i]) for i in range(len(chunks))]
    count = len(segments)
    stts = sample_tables[0][b"stts"]
    assert all(t[b"stts"] == stts for t in sample_tables)
    delta = U32.unpack_from(stts, 12)[0]
    overrides = {
        b"stts": table([(len(sizes),delta)]), b"stss": table([(i*30+1,) for i in range(count)]),
        b"stsc": table([(i+1,30,i+1) for i in range(count)]),
        b"stco": table([(offset,) for offset in offsets]),
        b"stsd": b"\0"*4 + U32.pack(count) + b"".join(t[b"stsd"][8:] for t in sample_tables),
        b"stsz": b"\0"*8 + U32.pack(len(sizes)) + b"".join(U32.pack(n) for n in sizes),
    }

    def rewrite(tree):
        result = bytearray()
        for name, value in boxes(tree):
            if name in (b"trak", b"mdia", b"minf", b"stbl"):
                value = rewrite(value)
            elif name in (b"mvhd",b"tkhd",b"mdhd"):
                assert value[0] == 0
                start = 20 if name == b"tkhd" else 16
                value = value[:start] + U32.pack(count*U32.unpack_from(value,start)[0]) + value[start+4:]
            elif name in overrides:
                value = overrides[name]
            result.extend(box(name,value))
        return result

    return ftyp + box(b"mdat", b"".join(chunks)) + box(b"moov", rewrite(path(data[0], b"moov")))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ffmpeg", required=True)
    ap.add_argument("--ffprobe", required=True)
    ap.add_argument("--out", type=Path, default=Path("testdata/hardware"))
    args = ap.parse_args()
    args.out.mkdir(exist_ok=True, parents=True)
    sizes = ["640x360","320x240","800x448"]
    colors = ["red","lime","blue"]
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)

        def encode(cmd, dest):
            subprocess.run([args.ffmpeg,"-v","error","-nostdin","-y",*cmd,str(dest.resolve())],cwd=root,check=True)

        for codec in ["h264","hevc"]:
            transport, parts = [], []
            for i, (size, color) in enumerate(zip(sizes,colors)):
                part = root/f"{codec}-{i}.mp4"
                opts = ["-c:v","libx264","-x264-params","repeat-headers=0:keyint=60:scenecut=0"] if codec == "h264" else [
                    "-c:v","libx265","-x265-params","pools=1:frame-threads=1:bframes=0:open-gop=0:keyint=60:scenecut=0:log-level=error","-tag:v","hvc1"]
                encode(["-f","lavfi","-i",f"color={color}:s={size}:r=25","-frames:v","30","-an","-threads","2",
                        *opts,"-bf","0","-video_track_timescale","25000","-use_editlist","0"],part)
                elem = root/f"{codec}-{i}.ts"
                encode(["-i",str(part),"-map","0:v","-c","copy","-bsf:v",f"{codec}_mp4toannexb","-f","mpegts"],elem)
                transport.append(elem)
                parts.append(part)
            combined = root/f"{codec}.ffconcat"
            combined.write_text("ffconcat version 1.0\n" + "".join(f"file '{p.name}'\n" for p in transport))
            encode(["-f","concat","-safe","0","-i",combined.name,"-map","0:v","-c","copy","-f","mpegts"],
                   args.out/f"H02-{codec}-inband.ts")
            (args.out/f"H02-{codec}-stsd.mp4").write_bytes(make_mp4(parts))
            if codec == "h264": (args.out/"H02-bad-length.mp4").write_bytes(make_mp4(parts[:1],True))
    files = {}
    for f in sorted(args.out.iterdir()):
        if f.suffix not in (".ts",".mp4"): continue
        info = subprocess.check_output([args.ffprobe,"-v","error","-select_streams","v","-show_packets",
                "-of","json",str(f)])
        packets = json.loads(info)["packets"]
        files[f.name] = {"sha256":hashlib.sha256(f.read_bytes()).hexdigest(),"bytes":f.stat().st_size,
                         "packets":len(packets),"keyPts":[p["pts_time"] for p in packets if "K" in p.get("flags","")],
                         "newExtradataPackets":[i for i,p in enumerate(packets) if any(s.get("side_data_type") == "New Extradata"
                                                     for s in p.get("side_data_list",[]))]}
    manifest = {"source":"project-generated 8-bit SDR solid colors at 25 fps","segments":
                [{"color":c,"size":s,"frames":30} for c,s in zip(colors,sizes)],"files":files}
    (args.out/"manifest.json").write_text(json.dumps(manifest,indent=2)+"\n",encoding="utf-8")


if __name__ == "__main__":
    main()
