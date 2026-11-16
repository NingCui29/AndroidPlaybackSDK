"""Generate additional B02 fixtures from synthetic project assets (desktop tools only)."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "testdata/baseline"
OUT = ROOT / "testdata/formats"

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ffmpeg", required=True, type=Path)
    parser.add_argument("--speech-one", type=Path)
    parser.add_argument("--speech-two", type=Path)
    parser.add_argument("--wmv-dir", type=Path, help="Add the two Media Foundation clips to the existing matrix")
    args = parser.parse_args()
    ffprobe = args.ffmpeg.with_name("ffprobe.exe" if args.ffmpeg.suffix else "ffprobe")
    OUT.mkdir(parents=True, exist_ok=True)
    manifest = OUT / "manifest.json"
    provenance = json.loads(manifest.read_text(encoding="utf-8"))["files"] if manifest.exists() else {}
    with tempfile.TemporaryDirectory() as tmp:
        temp = Path(tmp)
        def encode(name, arguments, temporary=False):
            destination = (temp if temporary else OUT) / name
            command = [str(args.ffmpeg), "-nostdin", "-hide_banner", "-loglevel", "error", "-y"]
            subprocess.run(command + list(map(str, arguments)) + [str(destination)], check=True)
            if not temporary:
                info = json.loads(subprocess.check_output([str(ffprobe), "-v", "error",
                    "-show_streams", "-show_format", "-of", "json", str(destination)], encoding="utf-8"))
                info["format"]["filename"] = name
                with destination.open("rb") as blob:
                    digest = hashlib.file_digest(blob, "sha256").hexdigest()
                provenance[name] = {"sha256": digest,
                    "source": "project-generated synthetic test patterns, narration or A04 PCM",
                    "probe": info}
            return destination

        wav = SRC / "A04.wav"
        if args.wmv_dir:
            for name, codec in [("V09a.wmv", "wmv3"), ("V09b.wmv", "vc1")]:
                encode(name, ["-i", args.wmv_dir / name, "-i", wav, "-map", "0:v:0", "-map", "1:a:0",
                        "-c:v", "copy", "-c:a", "wmav2", "-b:a", "128k"])
                streams = provenance[name]["probe"]["streams"]
                if [s["codec_name"] for s in streams] != [codec, "wmav2"]:
                    raise ValueError(f"Unexpected ASF tracks: {name}")
                provenance[name]["source"] = "Windows Media Foundation synthetic RGB test sequence + A04 PCM"
            save(manifest, args.ffmpeg, provenance)
            return
        if not args.speech_one or not args.speech_two:
            parser.error("provide both generated narration WAVs or --wmv-dir")
        video = ["-f","lavfi","-i","testsrc2=size=640x360:rate=25", "-i",wav,
                 "-map","0:v:0","-map","1:a:0","-t","8","-threads","4"]
        ruler = "drawbox=x=0:y=0:w=48:h=48:color=red:t=fill,drawbox=x=iw-48:y=0:w=48:h=48:color=blue:t=fill,drawgrid=w=64:h=36:t=1:c=white@0.5"
        mov = encode("ruler.mov", video + ["-vf",ruler,"-c:v","libx264","-g","25","-c:a","aac"], True)
        encode("V02.mov", ["-i",mov,"-map","0","-c","copy","-metadata:s:v:0","rotate=90"])
        hevc = encode("V03.mp4", video + ["-c:v","libx265","-x265-params",
                "pools=2:frame-threads=2:log-level=error:keyint=25","-tag:v","hvc1","-c:a","aac","-movflags","+faststart"])
        encode("V04.mkv", ["-i",hevc,"-map","0","-c","copy"])
        encode("V05.mkv", ["-i",mov,"-stream_loop","-1","-i",args.speech_one,
                "-stream_loop","-1","-i",args.speech_two,"-map","0:v","-map","1:a","-map","2:a",
                "-c:v","copy","-c:a","aac","-ar:a:0","44100","-ar:a:1","48000",
                "-metadata:s:a:0","language=eng","-metadata:s:a:0","title=Track one",
                "-metadata:s:a:1","language=eng","-metadata:s:a:1","title=Track two",
                "-disposition:a:0","default","-disposition:a:1","0","-t","8"])
        encode("V06.webm", video + ["-c:v","libvpx","-b:v","500k","-deadline","realtime","-c:a","libvorbis"])
        encode("V07.webm", video + ["-c:v","libvpx-vp9","-b:v","500k","-deadline","realtime","-cpu-used","6","-c:a","libopus"])
        encode("V08.avi", video + ["-c:v","mpeg4","-q:v","4","-c:a","libmp3lame"])
        encode("V10.ts", ["-i",mov,"-map","0","-c","copy","-bsf:v","h264_mp4toannexb","-f","mpegts"])
        encode("H02-hevc.ts", ["-i",hevc,"-map","0","-c","copy","-bsf:v","hevc_mp4toannexb","-f","mpegts"])
        cover = encode("cover.jpg", ["-f","lavfi","-i","color=c=blue:s=64x64","-frames:v","1"], True)
        encode("A01.mp3", ["-i",wav,"-i",cover,"-map","0:a","-map","1:v","-c:a","libmp3lame",
                "-c:v","copy","-id3v2_version","3","-metadata:s:v","comment=Cover (front)"])
        for name, codec in [("A03.flac","flac"),("A05a.ogg","libvorbis"),("A05b.ogg","libopus"),("A06.wma","wmav2")]:
            encode(name, ["-i",wav,"-c:a",codec])
    save(manifest, args.ffmpeg, provenance)

def save(manifest, ffmpeg, provenance):
    version = subprocess.check_output([str(ffmpeg), "-version"], encoding="utf-8").splitlines()[0]
    manifest.write_text(json.dumps({"generator":version,"files":provenance},
            indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

if __name__ == "__main__":
    main()
