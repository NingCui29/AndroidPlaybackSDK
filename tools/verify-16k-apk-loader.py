"""Exercise APK and AAB split native entries on the Android 15 16 KB native test guest."""
import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import subprocess
from zipfile import ZipFile, ZIP_STORED

from apk_native import libs_and_alignment

ROOT = Path(__file__).resolve().parents[1]
LIBS = ("libavutil.so", "libswresample.so", "libswscale.so", "libavcodec.so", "libavformat.so", "libplayback.so")
INIT = r"""
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/mount.h>
#include <sys/reboot.h>
#include <sys/wait.h>
#include <unistd.h>
int main(void) {
    mount("proc","/proc","proc",0,0);
    int tty=open("/dev/console",O_RDWR);
    if (tty >= 0) { dup2(tty,0); dup2(tty,1); dup2(tty,2); }
    setenv("ANDROID_ROOT","/system",1); setenv("ANDROID_DATA","/data",1);
    setenv("LD_LIBRARY_PATH","/apex/com.android.runtime/lib64/bionic:/apex/com.android.i18n/lib64:/system/lib64:/apex/com.android.runtime/lib64",1);
    int ok=1;
    const char *apk[]={"/probe/direct.apk","/probe/split.apk"};
    const char *offsets[]={"/probe/direct.txt","/probe/split.txt"};
    for (int i=0; i<2; ++i) {
        int status;
        pid_t pid=fork();
        if (!pid) { execl("/probe/loader","loader",apk[i],offsets[i],(char *)0); _exit(126); }
        if (pid<0 || waitpid(pid,&status,0)!=pid || !WIFEXITED(status) || WEXITSTATUS(status)) ok=0;
    }
    dprintf(1,"D06 APK-offset verification %s\n",ok?"PASS":"FAIL");
    reboot(RB_POWER_OFF); for (;;) pause();
}
"""


def sha(data):
    return hashlib.sha256(data).hexdigest()


def initramfs(template, replacements):
    data = gzip.decompress(template.read_bytes())
    result = bytearray()

    def add(name, payload, fields):
        label = name.encode() + b"\0"
        fields[6], fields[11] = len(payload), len(label)
        result.extend(b"070701" + b"".join(f"{v:08x}".encode() for v in fields) + label)
        result.extend(b"\0" * (-len(result) % 4))
        result.extend(payload)
        result.extend(b"\0" * (-len(result) % 4))

    pos = 0
    while data[pos:pos+6] == b"070701":
        fields = [int(data[j:j+8], 16) for j in range(pos+6, pos+110, 8)]
        start = pos + 110
        name = data[start:start+fields[11]-1].decode()
        start = (start + fields[11]+3) & ~3
        payload = data[start:start+fields[6]]
        pos = (start + fields[6]+3) & ~3
        if name == "TRAILER!!!":
            for ino, (path, content) in enumerate(replacements.items(), 700000):
                mode = 0o40755 if path == "probe" else 0o100755
                add(path, content, [ino, mode, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0])
            add(name, payload, fields)
            return gzip.compress(result, compresslevel=1, mtime=0)
        if name != "init" and not name.startswith("sdk/"):
            add(name, payload, fields)
    raise ValueError("Incomplete native test initramfs")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ("candidate", "consumer-run", "guest-tools", "ndk", "output"):
        parser.add_argument("--" + arg, required=True, type=Path)
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    guest = args.guest_tools.resolve()
    candidate = json.loads(args.candidate.read_text())
    host = json.loads((args.consumer_run / "summary.json").read_text())
    native = {name: record["sha256"] for name, record in candidate["nativeLibraries"].items()}
    if candidate["aar"]["sha256"] != host["aarSha256"] or set(native) != set(LIBS):
        raise ValueError("Build evidence does not match candidate")
    payloads = {"probe": b""}
    record = {"aarSha256": host["aarSha256"], "packages": {}}
    for label, case, file in [("direct", "aar-java", "host.apk"), ("split", "http-module-java", "host.apks")]:
        row = host["cases"][case]
        data = (args.consumer_run / case / file).read_bytes()
        if row["outcome"] != "PASS" or row["sdkSha256"] != host["aarSha256"] or sha(data) != row["files"][file]:
            raise ValueError("Host build or APK digest mismatch")
        entry = file
        if label == "split":
            entry, = row["aabSplitNative"]
            with ZipFile(io.BytesIO(data)) as bundle:
                data = bundle.read(entry)
        entries = libs_and_alignment(io.BytesIO(data), "lib/arm64-v8a/")
        if {name: x[0] for name, x in entries.items()} != native or any(
                x[1] != ZIP_STORED or x[2] % 16384 for x in entries.values()):
            raise ValueError("APK native entry digest, compression or alignment mismatch")
        offsets = {name: entries[name][2] for name in LIBS}
        record["packages"][label] = {"file": entry, "sha256": sha(data), "offsets": offsets}
        payloads["probe/"+label+".apk"] = data
        payloads["probe/"+label+".txt"] = "".join(f"{n} {v}\n" for n,v in offsets.items()).encode()
    ndk = args.ndk / "toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe"
    compiler = [ndk, "--target=aarch64-linux-android35", "-O2", "-Wall", "-Werror"]
    (out / "init.c").write_text(INIT)
    subprocess.run([*compiler, "-static", "-s", out/"init.c", "-o", out/"init"], check=True)
    subprocess.run([*compiler, ROOT/"native-tests/apk_offset_load.c", "-ldl", "-o", out/"loader"], check=True)
    # Link only against candidate bytes. The test guest receives the native libraries exclusively inside the APKs.
    libdir = out / "link"
    libdir.mkdir()
    with ZipFile(io.BytesIO(payloads["probe/direct.apk"])) as archive:
        for name in LIBS:
            (libdir/name).write_bytes(archive.read("lib/arm64-v8a/"+name))
    source = args.candidate.with_name("corresponding-source.zip")
    if sha(source.read_bytes()) != candidate["correspondingSourceSha256"]:
        raise ValueError("SDK native headers differ from candidate")
    headers = out / "include"
    headers.mkdir()
    with ZipFile(source) as archive:
        for header in ("playback_input.h", "software_decoder.h"):
            (headers/header).write_bytes(archive.read("sdk/src/main/cpp/"+header))
    subprocess.run([*compiler, "-shared", "-fPIC", "-Dmain=decode_count_main", "-I"+str(headers),
                    "-I"+str(ROOT/"third_party/ffmpeg/8.1.3/include"), ROOT/"native-tests/decode_count.c",
                    "-L"+str(libdir), "-lplayback", "-lavformat", "-lavcodec", "-lavutil", "-o", out/"pcm.so"], check=True)
    pcm = (ROOT/"testdata/baseline/A04.wav").read_bytes()
    manifest = json.loads((ROOT/"testdata/baseline/manifest.json").read_text())
    if sha(pcm) != manifest["files"]["A04.wav"]["sha256"]:
        raise ValueError("PCM fixture digest mismatch")
    payloads.update({"init": (out/"init").read_bytes(), "probe/loader": (out/"loader").read_bytes(),
                     "probe/pcm.so": (out/"pcm.so").read_bytes(), "probe/A04.wav": pcm})
    image = out / "boot.gz"
    image.write_bytes(initramfs(guest/"validate-initrd.gz", payloads))
    kernel = guest/"kernel-raw.img"
    record.update({"initramfsSha256": sha(image.read_bytes()), "kernelSha256": sha(kernel.read_bytes()),
                   "A04Sha256": sha(pcm)})
    with (out/"uart.txt").open("wb") as log, (out/"qemu-err.txt").open("wb") as err:
        subprocess.run([guest/"qemu11-verified/qemu-system-aarch64.exe", "-machine", "virt", "-cpu", "max",
                        "-smp", "2", "-m", "1536", "-nographic", "-no-reboot", "-monitor", "none",
                        "-kernel", kernel, "-initrd", image, "-append", "console=ttyAMA0 rdinit=/init loglevel=3"],
                       stdout=log, stderr=err, check=True, timeout=300)
    log = (out/"uart.txt").read_text(errors="replace")
    passed = "D06 APK-offset verification PASS" in log and log.count("page=16384 FFmpeg=8.1.3 PCM=PASS") == 2
    record.update({"outcome": "PASS" if passed else "FAIL", "uartSha256": sha((out/"uart.txt").read_bytes()),
                   "scope": "Native APK entry loading and PCM decoding; no Package Manager, AudioTrack or Surface."})
    (out/"report.json").write_text(json.dumps(record, indent=2)+"\n")
    if not passed:
        raise SystemExit("Guest verification failed: " + str(out/"uart.txt"))
    print(out/"report.json")


if __name__ == "__main__":
    main()
