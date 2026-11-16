"""Run S01 PCM, S02 RGBA and H04 cache checks against one sealed arm64 Android SDK."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import wave
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
# Reuse the native guest archive builder used for the 16 KB APK load checks.
spec = importlib.util.spec_from_file_location("apk_guest", Path(__file__).with_name("verify-16k-apk-loader.py"))
guest = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guest)

INIT = r"""
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/mount.h>
#include <sys/reboot.h>
#include <sys/wait.h>
#include <unistd.h>
static int run(char *const args[]) {
    int status;
    pid_t child=fork();
    if (!child) { execv(args[0],args); _exit(126); }
    return child>0 && waitpid(child,&status,0)==child && WIFEXITED(status) && WEXITSTATUS(status)==0;
}
int main(void) {
    mount("proc","/proc","proc",0,0);
    int tty=open("/dev/console",O_RDWR);
    if (tty>=0) { dup2(tty,0); dup2(tty,1); dup2(tty,2); }
    setenv("ANDROID_ROOT","/system",1); setenv("ANDROID_DATA","/data",1);
    setenv("LD_LIBRARY_PATH","/probe:/apex/com.android.runtime/lib64/bionic:/apex/com.android.i18n/lib64:/system/lib64:/apex/com.android.runtime/lib64",1);
    int ok=run((char *const[]){"/probe/rgba",NULL});
    ok &= run((char *const[]){"/probe/pcm","/probe/A04.wav","/probe/A02.m4a","PCM_DIGEST",NULL});
    ok &= run((char *const[]){"/probe/cache",NULL});
    dprintf(1,"Native output verification page=%ld %s\n",sysconf(_SC_PAGESIZE),ok?"PASS":"FAIL");
    reboot(RB_POWER_OFF); for (;;) pause();
}
"""


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("candidate", "ndk", "guest-tools", "output"):
        parser.add_argument("--"+name, required=True, type=Path)
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    libs = out/"libs"
    headers = out/"headers"
    libs.mkdir()
    headers.mkdir()
    metadata = json.loads(args.candidate.read_text())
    aar = args.candidate.with_name(metadata["aar"]["name"])
    source = args.candidate.with_name("corresponding-source.zip")
    if sha(aar.read_bytes()) != metadata["aar"]["sha256"] or sha(source.read_bytes()) != metadata["correspondingSourceSha256"]:
        raise ValueError("Candidate binary/source archive digest mismatch")
    with ZipFile(source) as archive:
        for name in archive.namelist():
            if name.startswith("sdk/src/main/cpp/") and name.endswith(".h"):
                (headers/Path(name).name).write_bytes(archive.read(name))
    payload = {"probe": b""}
    with ZipFile(aar) as archive:
        for name, record in metadata["nativeLibraries"].items():
            binary = archive.read("jni/arm64-v8a/"+name)
            if sha(binary) != record["sha256"]:
                raise ValueError("Native library digest mismatch")
            (libs/name).write_bytes(binary)
            payload["probe/"+name] = binary
    fixtures = ROOT/"testdata/baseline"
    manifest = json.loads((fixtures/"manifest.json").read_text())["files"]
    for name in ("A04.wav","A02.m4a"):
        payload["probe/"+name] = (fixtures/name).read_bytes()
        if sha(payload["probe/"+name]) != manifest[name]["sha256"]:
            raise ValueError("Fixture digest mismatch")
    with wave.open(str(fixtures/"A04.wav"),"rb") as wav:
        pcm_digest = sha(wav.readframes(wav.getnframes()))
    (out/"init.c").write_text(INIT.replace("PCM_DIGEST", pcm_digest))
    cc = [args.ndk/"toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe", "--target=aarch64-linux-android35",
          "-O2", "-Wall", "-Werror"]
    subprocess.run([*cc,"-static","-s",out/"init.c","-o",out/"init"], check=True)
    payload["init"] = (out/"init").read_bytes()
    checks = {"rgba": "rgba_checks.c", "pcm": "pcm_output_checks.c", "cache": "recovery_cache.c"}
    for label, src in checks.items():
        subprocess.run([*cc,"-I"+str(headers),"-I"+str(ROOT/"third_party/ffmpeg/8.1.3/include"),
                        ROOT/"native-tests"/src,"-L"+str(libs),"-lplayback","-lavformat","-lavcodec","-lavutil",
                        "-lswresample","-lm","-o",out/label], check=True)
        payload["probe/"+label] = (out/label).read_bytes()
    image = out/"ramfs.gz"
    image.write_bytes(guest.initramfs(args.guest_tools/"validate-initrd.gz",payload))
    kernel = args.guest_tools/"kernel-raw.img"
    with (out/"uart.txt").open("wb") as log, (out/"qemu-err.txt").open("wb") as err:
        subprocess.run([args.guest_tools/"qemu11-verified/qemu-system-aarch64.exe", "-machine","virt","-cpu","max",
                        "-smp","2","-m","1536","-nographic","-no-reboot","-monitor","none","-kernel",kernel,
                        "-initrd",image,"-append","console=ttyAMA0 rdinit=/init loglevel=3"], stdout=log,
                       stderr=err, check=True, timeout=180)
    uart = (out/"uart.txt").read_text(errors="replace")
    passed = "Native output verification page=16384 PASS" in uart
    result = {"status":"PASS" if passed else "FAIL","aarSha256":metadata["aar"]["sha256"],
              "correspondingSourceSha256":metadata["correspondingSourceSha256"],"kernelSha256":sha(kernel.read_bytes()),
              "initramfsSha256":sha(image.read_bytes()),"uartSha256":sha((out/"uart.txt").read_bytes()),
              "guestPayloads":{n:sha(b) for n,b in payload.items() if n != "probe"},
              "testSources":{n:sha((ROOT/"native-tests"/n).read_bytes()) for n in checks.values()},
              "expectedWavPcmSha256":pcm_digest,"scope":"Digital PCM, RGBA and cache functions; no Android framework or physical output."}
    (out/"report.json").write_text(json.dumps(result,indent=2)+"\n",encoding="utf-8")
    if not passed:
        raise SystemExit("Native output checks failed; inspect " + str(out/"uart.txt"))
    print(out/"report.json")


if __name__ == "__main__":
    main()
